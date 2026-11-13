import glob
import os
import platform
import shutil
import subprocess
import sys

from build_dependencies.common import delete_file_if_exists, error, log
from build_dependencies.bootstrap_release_libs import DEFAULT_OUTPUT_DIR, find_ndk_build
from build_dependencies.values import (
    Arch,
    ARCH_NAMES,
    DEPOT_TOOLS_GIT_URL,
    FILE_NAMES,
    Lib,
    LIB_EXTENSION,
    PDFIUM_BRANCH,
    PDFIUM_GIT_URL,
    PDFIUM_GN_ARGS,
    PDFIUM_GN_CPU_NAMES,
    PDFIUM_SOURCE_DIR,
)

DEPOT_TOOLS_DIR = "depot_tools"

PDFIUM_PATCHES = [
    (os.path.join("patches", "shared_library.patch"), "."),
    (os.path.join("patches", "public_headers.patch"), "."),
    (os.path.join("patches", "annotation_reply.patch"), "."),
    (os.path.join("patches", "clang_rt.patch"), "build"),
    (os.path.join("patches", "android", "build.patch"), "build"),
]


def build_pdfium_from_source():
    root = os.getcwd()
    env = depot_tools_env(root)
    source = sync_pdfium(root, env)
    for arch in [Arch.arm64, Arch.armeabi, Arch.x86_64, Arch.x86]:
        build_arch(source, arch, env)


def depot_tools_env(root):
    depot_tools = os.path.join(root, DEPOT_TOOLS_DIR)
    if not os.path.isdir(depot_tools):
        run(["git", "clone", "--depth", "1", DEPOT_TOOLS_GIT_URL, depot_tools], root, os.environ.copy())

    env = os.environ.copy()
    env["PATH"] = depot_tools + os.pathsep + env["PATH"]
    return env


def sync_pdfium(root, env):
    checkout = os.path.join(root, PDFIUM_SOURCE_DIR)
    source = os.path.join(checkout, "pdfium")

    os.makedirs(checkout, exist_ok=True)

    if not os.path.isfile(os.path.join(checkout, ".gclient")):
        run(
            [
                "gclient",
                "config",
                "--unmanaged",
                PDFIUM_GIT_URL,
                "--custom-var",
                "checkout_configuration=minimal",
            ],
            checkout,
            env,
        )

        with open(os.path.join(checkout, ".gclient"), "a") as config:
            config.write("target_os = ['android']\n")

    reset_patched_repositories(source, env)
    run(["gclient", "sync", "-r", f"origin/{PDFIUM_BRANCH}", "--no-history", "--shallow"], checkout, env)
    restore_native_llvm_toolchain(source, env)
    apply_pdfium_patches(root, source, env)

    return source


def restore_native_llvm_toolchain(source, env):
    if sys.platform != "darwin":
        return

    llvm_dir = os.path.join(source, "third_party", "llvm-build", "Release+Asserts")
    mac_package = "Mac_arm64" if platform.machine() == "arm64" else "Mac"
    archives = glob.glob(os.path.join(llvm_dir, f".{mac_package}_clang-*.tar.xz"))
    if len(archives) != 1:
        error(
            f"Expected one native macOS LLVM archive in {llvm_dir}, "
            f"found {len(archives)}."
        )

    # Android sync also downloads the Linux compiler package because it
    # contains Android compiler-rt libraries. Both packages target the same
    # directory, and gclient can leave the Linux executables on top. Overlay
    # the native macOS package while preserving those Android runtime files.
    run(["tar", "-xJf", archives[0], "-C", llvm_dir], source, env)
    log(f"Restored native {mac_package} LLVM executables.")

    ndk_prebuilt_dir = os.path.join(
        source,
        "third_party",
        "android_toolchain",
        "ndk",
        "toolchains",
        "llvm",
        "prebuilt",
    )
    linux_toolchain = os.path.join(ndk_prebuilt_dir, "linux-x86_64")
    mac_toolchain = os.path.join(ndk_prebuilt_dir, "darwin-x86_64")
    if not os.path.exists(mac_toolchain):
        if not os.path.isdir(linux_toolchain):
            error(f"Missing Android NDK sysroot at {linux_toolchain}.")
        # Chromium's CIPD Android toolchain package contains a single,
        # host-independent target sysroot under the Linux directory name.
        # GN selects a darwin directory on macOS, so expose that same sysroot
        # there; compilation itself still uses the native LLVM package above.
        os.symlink("linux-x86_64", mac_toolchain)
        log("Exposed the Android target sysroot to the macOS host toolchain.")

    # The macOS LLVM archive intentionally omits llvm-nm, while PDFium's
    # shared-library wrapper invokes it after linking. Replace the Linux copy
    # supplied for Android runtimes with the native tool from the installed
    # Android NDK.
    ndk_root = os.path.dirname(str(find_ndk_build(None)))
    native_nm_candidates = glob.glob(
        os.path.join(
            ndk_root,
            "toolchains",
            "llvm",
            "prebuilt",
            "darwin-*",
            "bin",
            "llvm-nm",
        )
    )
    if len(native_nm_candidates) != 1:
        error(
            f"Expected one native llvm-nm under {ndk_root}, "
            f"found {len(native_nm_candidates)}."
        )
    shutil.copy2(native_nm_candidates[0], os.path.join(llvm_dir, "bin", "llvm-nm"))
    log("Restored native llvm-nm for PDFium's shared-library wrapper.")


def reset_patched_repositories(source, env):
    if not os.path.isdir(source):
        return

    for repository in patched_repositories(source, env):
        run(["git", "reset", "--hard", "HEAD"], repository, env)
        delete_patch_artifacts(repository)

    log("Reset PDFium source before sync.")


def patched_repositories(source, env):
    repositories = []

    for _, patch_dir in PDFIUM_PATCHES:
        directory = get_patch_cwd(source, patch_dir)
        repository = git_repository_root(directory, env)

        if repository is not None and repository not in repositories:
            repositories.append(repository)

    return repositories


def git_repository_root(directory, env):
    if not os.path.isdir(directory):
        return None

    result = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"],
        cwd=directory,
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
    )

    if result.returncode != 0:
        return None

    return os.path.abspath(result.stdout.strip())


def delete_patch_artifacts(directory):
    for root, dirs, files in os.walk(directory):
        if ".git" in dirs:
            dirs.remove(".git")

        for file in files:
            if file.endswith(".orig") or file.endswith(".rej"):
                os.remove(os.path.join(root, file))


def apply_pdfium_patches(root, source, env):
    for patch_file, patch_dir in PDFIUM_PATCHES:
        apply_patch(root, source, patch_file, patch_dir, env)


def apply_patch(root, source, patch_file, patch_dir, env):
    patch_path = get_patch_path(root, patch_file)
    patch_cwd = get_patch_cwd(source, patch_dir)

    if not os.path.isfile(patch_path):
        error(f"Missing PDFium patch file: {patch_path}")

    if not os.path.isdir(patch_cwd):
        error(f"Missing PDFium patch directory: {patch_cwd}")

    run(["patch", "--forward", "--batch", "-p1", "-i", patch_path], patch_cwd, env)
    log(f"Applied patch: {patch_file}")


def get_patch_path(root, patch_file):
    return os.path.join(root, patch_file)


def get_patch_cwd(source, patch_dir):
    return os.path.join(source, patch_dir)


def build_arch(source, arch, env):
    log(f"Building PDFium from source for {ARCH_NAMES[arch]}.")
    out_dir = os.path.join("out", ARCH_NAMES[arch])

    write_gn_args(os.path.join(source, out_dir), arch)
    run(["gn", "gen", out_dir], source, env)
    run(["ninja", "-C", out_dir, "pdfium"], source, env)
    install_lib(os.path.join(source, out_dir), arch)

    log(f"Finished building PDFium for {ARCH_NAMES[arch]}.")
    log("------------------------------------")


def write_gn_args(out_dir, arch):
    os.makedirs(out_dir, exist_ok=True)

    args = PDFIUM_GN_ARGS + [f'target_cpu = "{PDFIUM_GN_CPU_NAMES[arch]}"']
    if sys.platform == "darwin":
        # This PDFium revision initializes use_system_xcode to an empty
        # string for Android targets. It is later consumed by the macOS host
        # toolchain as a boolean, which recent GN versions reject.
        args.append("use_system_xcode = true")
    with open(os.path.join(out_dir, "args.gn"), "w") as args_file:
        args_file.write("\n".join(args) + "\n")


def install_lib(out_dir, arch):
    built_lib = os.path.join(out_dir, "libpdfium.so")

    if not os.path.isfile(built_lib):
        error(f"Build produced no library at {built_lib}")

    lib_path = os.path.join(
        str(DEFAULT_OUTPUT_DIR),
        ARCH_NAMES[arch],
        FILE_NAMES[Lib.pdfium] + LIB_EXTENSION,
    )

    delete_file_if_exists(lib_path)
    os.makedirs(os.path.dirname(lib_path), exist_ok=True)
    shutil.copy(built_lib, lib_path)

    log(f"Installed {lib_path}.")


def run(command, cwd, env):
    log("Run: " + " ".join(command))

    result = subprocess.run(command, cwd=cwd, env=env)
    if result.returncode != 0:
        error(f"'{command[0]}' failed with code {result.returncode}")


if __name__ == "__main__":
    build_pdfium_from_source()

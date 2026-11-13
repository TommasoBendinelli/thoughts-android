// Written by Mudlej. License is GPLv3.
package com.github.barteksc.pdfviewer.preview;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class PreviewDiskCacheTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void writeThenRead_roundTripsBytes() throws Exception {
        PreviewDiskCache disk = new PreviewDiskCache(folder.getRoot());
        PreviewKey key = new PreviewKey(0, 512, 0);
        byte[] data = {1, 2, 3, 4};
        disk.write("doc", key, data);
        assertArrayEquals(data, disk.read("doc", key));
    }

    @Test
    public void read_missingFileReturnsNull() {
        PreviewDiskCache disk = new PreviewDiskCache(folder.getRoot());
        assertNull(disk.read("doc", new PreviewKey(9, 512, 0)));
    }

    @Test
    public void read_updatesModifiedTime() throws Exception {
        PreviewDiskCache disk = new PreviewDiskCache(folder.getRoot());
        PreviewKey key = new PreviewKey(0, 512, 0);
        disk.write("doc", key, new byte[]{1});
        File file = disk.fileFor("doc", key);
        file.setLastModified(1000L);

        disk.read("doc", key);

        assertTrue(file.lastModified() > 1000L);
    }

    @Test
    public void delete_removesFile() {
        PreviewDiskCache disk = new PreviewDiskCache(folder.getRoot());
        PreviewKey key = new PreviewKey(0, 512, 0);
        disk.write("doc", key, new byte[]{1});
        assertTrue(disk.exists("doc", key));

        disk.delete("doc", key);

        assertFalse(disk.exists("doc", key));
    }

    @Test
    public void trim_deletesOldestByModifiedTimeAcrossDocKeys() {
        PreviewDiskCache disk = new PreviewDiskCache(folder.getRoot());
        PreviewKey oldest = new PreviewKey(0, 512, 0);
        PreviewKey middle = new PreviewKey(1, 512, 0);
        PreviewKey newest = new PreviewKey(2, 512, 0);
        byte[] payload = new byte[100];

        disk.write("docA", oldest, payload);
        disk.write("docB", middle, payload);
        disk.write("docA", newest, payload);
        disk.fileFor("docA", oldest).setLastModified(1000L);
        disk.fileFor("docB", middle).setLastModified(2000L);
        disk.fileFor("docA", newest).setLastModified(3000L);

        disk.trim(250L);

        assertFalse(disk.exists("docA", oldest));
        assertTrue(disk.exists("docB", middle));
        assertTrue(disk.exists("docA", newest));
    }

    @Test
    public void trim_underBudgetKeepsEverything() {
        PreviewDiskCache disk = new PreviewDiskCache(folder.getRoot());
        PreviewKey a = new PreviewKey(0, 512, 0);
        PreviewKey b = new PreviewKey(1, 512, 0);
        disk.write("doc", a, new byte[50]);
        disk.write("doc", b, new byte[50]);

        disk.trim(1000L);

        assertTrue(disk.exists("doc", a));
        assertTrue(disk.exists("doc", b));
    }

    @Test
    public void sort_snapshotsEachTimestampOnce() {
        List<File> files = new ArrayList<>();
        List<ChangingTimestampFile> changingFiles = new ArrayList<>();
        for (int i = 0; i < 128; i++) {
            ChangingTimestampFile file = new ChangingTimestampFile(folder.getRoot(), "preview-" + i, i);
            files.add(file);
            changingFiles.add(file);
        }

        PreviewDiskCache.sortByLastModifiedSnapshot(files);

        for (ChangingTimestampFile file : changingFiles) {
            assertTrue(file.lastModifiedCalls == 1);
        }
    }

    private static final class ChangingTimestampFile extends File {
        private final long initialTimestamp;
        int lastModifiedCalls;

        ChangingTimestampFile(File parent, String child, long initialTimestamp) {
            super(parent, child);
            this.initialTimestamp = initialTimestamp;
        }

        @Override
        public long lastModified() {
            lastModifiedCalls++;
            return lastModifiedCalls % 2 == 0 ? Long.MAX_VALUE - initialTimestamp : initialTimestamp;
        }
    }
}

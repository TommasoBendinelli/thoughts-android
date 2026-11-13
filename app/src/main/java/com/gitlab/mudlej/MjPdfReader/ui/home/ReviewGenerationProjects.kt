// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.google.gson.JsonArray

internal data class ReviewGenerationProject(
    val id: String,
    val label: String,
    val filename: String,
    val documentFilenames: Set<String>,
) {
    fun containsPdf(filename: String?) = filename != null &&
        (this.filename == filename || filename in documentFilenames)

    val basicDocumentFilenames: List<String> get() = (documentFilenames + filename).sorted()

    override fun toString() = label
}

internal object ReviewGenerationProjects {
    fun choices(projects: JsonArray, currentPdfs: Map<String, String>): List<ReviewGenerationProject> {
        val eligible = projects.mapNotNull { value ->
            val project = value.asJsonObject
            val target = project.get("target")?.asString.orEmpty()
            // Use the complete project target, never an arbitrary chapter or imported PDF.
            if (target !in currentPdfs) return@mapNotNull null
            ReviewGenerationProject(
                id = project.get("id").asString,
                label = project.get("name")?.asString?.takeIf(String::isNotBlank)
                    ?: target.removeSuffix(".pdf"),
                filename = target,
                documentFilenames = project.getAsJsonArray("documents")?.mapNotNull {
                    it.asJsonObject.get("filename")?.asString
                }?.toSet().orEmpty(),
            )
        }.distinctBy { it.id }
        val duplicateLabels = eligible.groupingBy { it.label.lowercase() }.eachCount()
        return eligible.map {
            if (duplicateLabels.getValue(it.label.lowercase()) > 1) it.copy(label = "${it.label} (${it.id})") else it
        }.sortedBy { it.label.lowercase() }
    }
}

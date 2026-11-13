// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import android.graphics.RectF
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class AnnotationFragment(
    val page: Int,
    val rects: List<RectF>,
    val selectedText: String = "",
)

sealed class AnnotationEdit {

    abstract val page: Int

    fun isPdfCommentEdit(): Boolean = when (this) {
        is AddPdfComment,
        is AddPdfMarkupComment,
        is AddPdfReply,
        is SetPdfCommentContents,
        is SetPdfCommentMetadata,
        is DeletePdfComment -> true
        else -> false
    }

    data class Add(
        override val page: Int,
        val group: String,
        val rects: List<RectF>,
        val color: Int,
        val contents: String,
        val createdDate: String? = null,
        val fragments: List<AnnotationFragment> =
            listOf(AnnotationFragment(page, rects, contents)),
    ) : AnnotationEdit()

    data class Recolor(
        override val page: Int,
        val group: String,
        val color: Int,
    ) : AnnotationEdit()

    data class SetNote(
        override val page: Int,
        val group: String,
        val note: String,
        val modifiedDate: String? = null,
    ) : AnnotationEdit()

    data class Delete(
        override val page: Int,
        val group: String,
    ) : AnnotationEdit()

    data class AddPdfComment(
        override val page: Int,
        val name: String,
        val rect: RectF,
        val contents: String,
        val author: String,
        val createdDate: String,
        val modifiedDate: String,
        val priority: CommentPriority = CommentPriority.NORMAL,
        val location: CommentLocation? = null,
        val color: Int = CommentCreationMetadata.DEFAULT_POPUP_COLOR,
        val needsAction: Boolean = false,
    ) : AnnotationEdit()

    data class AddPdfMarkupComment(
        override val page: Int,
        val name: String,
        val rects: List<RectF>,
        val color: Int,
        val contents: String,
        val author: String,
        val createdDate: String,
        val modifiedDate: String,
        val selectedText: String = "",
        val fragments: List<AnnotationFragment> =
            listOf(AnnotationFragment(page, rects, selectedText)),
        val priority: CommentPriority = CommentPriority.NORMAL,
        val location: CommentLocation? = null,
        val subtype: Int = com.github.barteksc.pdfviewer.PDFView.PdfComment.SUBTYPE_HIGHLIGHT,
        val needsAction: Boolean = false,
    ) : AnnotationEdit()

    data class AddPdfReply(
        override val page: Int,
        val name: String,
        val parentName: String,
        val parentAnnotationIndex: Int,
        val parentSubtype: Int,
        val rect: RectF,
        val contents: String,
        val author: String,
        val createdDate: String,
        val modifiedDate: String,
    ) : AnnotationEdit()

    data class SetPdfCommentContents(
        override val page: Int,
        val annotationIndex: Int,
        val subtype: Int,
        val name: String,
        val contents: String,
        val modifiedDate: String,
    ) : AnnotationEdit()

    data class SetPdfCommentMetadata(
        override val page: Int,
        val annotationIndex: Int,
        val subtype: Int,
        val name: String,
        val priority: CommentPriority,
        val location: CommentLocation?,
        val modifiedDate: String,
        val color: Int? = null,
        val rect: RectF? = null,
        val needsAction: Boolean? = null,
    ) : AnnotationEdit()

    data class DeletePdfComment(
        override val page: Int,
        val annotationIndex: Int,
        val subtype: Int,
        val name: String,
    ) : AnnotationEdit()

    data class SetFieldText(
        override val page: Int,
        val fieldIndex: Int,
        val fieldName: String,
        val text: String,
    ) : AnnotationEdit()

    data class SetFieldChecked(
        override val page: Int,
        val fieldIndex: Int,
        val fieldName: String,
        val checked: Boolean,
    ) : AnnotationEdit()

    data class AddSignature(
        override val page: Int,
        val rect: RectF,
        val strokes: List<FloatArray>,
        val color: Int,
        val strokeWidth: Float,
    ) : AnnotationEdit()

    fun toJsonLine(): String {
        val json = JsonObject()
        json.addProperty(KEY_OP, opName())
        json.addProperty(KEY_PAGE, page)
        when (this) {
            is Add -> {
                json.addProperty(KEY_GROUP, group)
                json.addProperty(KEY_COLOR, color)
                json.addProperty(KEY_CONTENTS, contents)
                json.add(KEY_RECTS, rectsToJson(rects))
                json.add(KEY_FRAGMENTS, fragmentsToJson(fragments))
                createdDate?.let { json.addProperty(KEY_CREATED, it) }
            }
            is Recolor -> {
                json.addProperty(KEY_GROUP, group)
                json.addProperty(KEY_COLOR, color)
            }
            is SetNote -> {
                json.addProperty(KEY_GROUP, group)
                json.addProperty(KEY_NOTE, note)
                modifiedDate?.let { json.addProperty(KEY_MODIFIED, it) }
            }
            is Delete -> json.addProperty(KEY_GROUP, group)
            is AddPdfComment -> {
                json.addProperty(KEY_NAME, name)
                json.add(KEY_RECT, rectToJson(rect))
                json.addProperty(KEY_CONTENTS, contents)
                json.addProperty(KEY_AUTHOR, author)
                json.addProperty(KEY_CREATED, createdDate)
                json.addProperty(KEY_MODIFIED, modifiedDate)
                json.addProperty(KEY_COLOR, color)
                json.addProperty(KEY_PRIORITY, priority.wireValue)
                json.addProperty("needs_action", needsAction)
                location?.let { json.add(KEY_LOCATION, it.toJson()) }
            }
            is AddPdfMarkupComment -> {
                json.addProperty(KEY_SUBTYPE, subtype)
                json.addProperty(KEY_NAME, name)
                json.add(KEY_RECTS, rectsToJson(rects))
                json.addProperty(KEY_COLOR, color)
                json.addProperty(KEY_CONTENTS, contents)
                json.addProperty(KEY_AUTHOR, author)
                json.addProperty(KEY_CREATED, createdDate)
                json.addProperty(KEY_MODIFIED, modifiedDate)
                json.addProperty(KEY_SELECTED_TEXT, selectedText)
                json.add(KEY_FRAGMENTS, fragmentsToJson(fragments))
                json.addProperty(KEY_PRIORITY, priority.wireValue)
                json.addProperty("needs_action", needsAction)
                location?.let { json.add(KEY_LOCATION, it.toJson()) }
            }
            is AddPdfReply -> {
                json.addProperty(KEY_NAME, name)
                json.addProperty(KEY_PARENT_NAME, parentName)
                json.addProperty(KEY_PARENT_ANNOTATION_INDEX, parentAnnotationIndex)
                json.addProperty(KEY_PARENT_SUBTYPE, parentSubtype)
                json.add(KEY_RECT, rectToJson(rect))
                json.addProperty(KEY_CONTENTS, contents)
                json.addProperty(KEY_AUTHOR, author)
                json.addProperty(KEY_CREATED, createdDate)
                json.addProperty(KEY_MODIFIED, modifiedDate)
            }
            is SetPdfCommentContents -> {
                json.addProperty(KEY_ANNOTATION_INDEX, annotationIndex)
                json.addProperty(KEY_SUBTYPE, subtype)
                json.addProperty(KEY_NAME, name)
                json.addProperty(KEY_CONTENTS, contents)
                json.addProperty(KEY_MODIFIED, modifiedDate)
            }
            is SetPdfCommentMetadata -> {
                json.addProperty(KEY_ANNOTATION_INDEX, annotationIndex)
                json.addProperty(KEY_SUBTYPE, subtype)
                json.addProperty(KEY_NAME, name)
                json.addProperty(KEY_PRIORITY, priority.wireValue)
                needsAction?.let { json.addProperty("needs_action", it) }
                location?.let { json.add(KEY_LOCATION, it.toJson()) }
                color?.let { json.addProperty(KEY_COLOR, it) }
                rect?.let { json.add(KEY_RECT, rectToJson(it)) }
                json.addProperty(KEY_MODIFIED, modifiedDate)
            }
            is DeletePdfComment -> {
                json.addProperty(KEY_ANNOTATION_INDEX, annotationIndex)
                json.addProperty(KEY_SUBTYPE, subtype)
                json.addProperty(KEY_NAME, name)
            }
            is SetFieldText -> {
                json.addProperty(KEY_FIELD_INDEX, fieldIndex)
                json.addProperty(KEY_FIELD_NAME, fieldName)
                json.addProperty(KEY_TEXT, text)
            }
            is SetFieldChecked -> {
                json.addProperty(KEY_FIELD_INDEX, fieldIndex)
                json.addProperty(KEY_FIELD_NAME, fieldName)
                json.addProperty(KEY_CHECKED, checked)
            }
            is AddSignature -> {
                json.addProperty(KEY_COLOR, color)
                json.addProperty(KEY_STROKE_WIDTH, strokeWidth)
                json.add(KEY_RECT, rectToJson(rect))
                json.add(KEY_STROKES, strokesToJson(strokes))
            }
        }
        return json.toString()
    }

    private fun opName(): String = when (this) {
        is Add -> OP_ADD
        is Recolor -> OP_RECOLOR
        is SetNote -> OP_SET_NOTE
        is Delete -> OP_DELETE
        is AddPdfComment -> OP_ADD_PDF_COMMENT
        is AddPdfMarkupComment -> OP_ADD_PDF_HIGHLIGHT_COMMENT
        is AddPdfReply -> OP_ADD_PDF_REPLY
        is SetPdfCommentContents -> OP_SET_PDF_COMMENT_CONTENTS
        is SetPdfCommentMetadata -> OP_SET_PDF_COMMENT_METADATA
        is DeletePdfComment -> OP_DELETE_PDF_COMMENT
        is SetFieldText -> OP_SET_FIELD_TEXT
        is SetFieldChecked -> OP_SET_FIELD_CHECKED
        is AddSignature -> OP_ADD_SIGNATURE
    }

    companion object {
        private const val KEY_OP = "op"
        private const val KEY_PAGE = "page"
        private const val KEY_GROUP = "group"
        private const val KEY_COLOR = "color"
        private const val KEY_CONTENTS = "contents"
        private const val KEY_RECTS = "rects"
        private const val KEY_FRAGMENTS = "fragments"
        private const val KEY_SELECTED_TEXT = "selectedText"
        private const val KEY_CREATED = "created"
        private const val KEY_NOTE = "note"
        private const val KEY_MODIFIED = "modified"
        private const val KEY_ANNOTATION_INDEX = "annotationIndex"
        private const val KEY_SUBTYPE = "subtype"
        private const val KEY_NAME = "name"
        private const val KEY_PARENT_NAME = "parentName"
        private const val KEY_PARENT_ANNOTATION_INDEX = "parentAnnotationIndex"
        private const val KEY_PARENT_SUBTYPE = "parentSubtype"
        private const val KEY_AUTHOR = "author"
        private const val KEY_PRIORITY = "priority"
        private const val KEY_LOCATION = "location"
        private const val KEY_FIELD_INDEX = "fieldIndex"
        private const val KEY_FIELD_NAME = "fieldName"
        private const val KEY_TEXT = "text"
        private const val KEY_CHECKED = "checked"
        private const val OP_ADD = "add"
        private const val OP_RECOLOR = "recolor"
        private const val OP_SET_NOTE = "setNote"
        private const val OP_DELETE = "delete"
        private const val OP_ADD_PDF_COMMENT = "addPdfComment"
        private const val OP_ADD_PDF_HIGHLIGHT_COMMENT = "addPdfHighlightComment"
        private const val OP_ADD_PDF_REPLY = "addPdfReply"
        private const val OP_SET_PDF_COMMENT_CONTENTS = "setPdfCommentContents"
        private const val OP_SET_PDF_COMMENT_METADATA = "setPdfCommentMetadata"
        private const val OP_DELETE_PDF_COMMENT = "deletePdfComment"
        private const val KEY_RECT = "rect"
        private const val KEY_STROKES = "strokes"
        private const val KEY_STROKE_WIDTH = "strokeWidth"
        private const val OP_SET_FIELD_TEXT = "setFieldText"
        private const val OP_SET_FIELD_CHECKED = "setFieldChecked"
        private const val OP_ADD_SIGNATURE = "addSignature"

        fun fromJsonLine(line: String): AnnotationEdit? = runCatching {
            val json = JsonParser.parseString(line).asJsonObject
            val page = json.get(KEY_PAGE).asInt
            when (json.get(KEY_OP).asString) {
                OP_ADD -> Add(
                    page = page,
                    group = json.get(KEY_GROUP).asString,
                    rects = rectsFromJson(json.getAsJsonArray(KEY_RECTS)),
                    color = json.get(KEY_COLOR).asInt,
                    contents = json.get(KEY_CONTENTS).asString,
                    createdDate = optionalString(json, KEY_CREATED),
                    fragments = fragmentsFromJson(json, page, KEY_RECTS, KEY_CONTENTS),
                )
                OP_RECOLOR -> Recolor(
                    page = page,
                    group = json.get(KEY_GROUP).asString,
                    color = json.get(KEY_COLOR).asInt,
                )
                OP_SET_NOTE -> SetNote(
                    page = page,
                    group = json.get(KEY_GROUP).asString,
                    note = json.get(KEY_NOTE).asString,
                    modifiedDate = optionalString(json, KEY_MODIFIED),
                )
                OP_DELETE -> Delete(page = page, group = json.get(KEY_GROUP).asString)
                OP_ADD_PDF_COMMENT -> AddPdfComment(
                    page = page,
                    needsAction = json.get("needs_action")?.takeUnless { it.isJsonNull }?.asBoolean ?: false,
                    name = json.get(KEY_NAME).asString,
                    rect = rectFromJson(json.getAsJsonArray(KEY_RECT)),
                    contents = json.get(KEY_CONTENTS).asString,
                    author = json.get(KEY_AUTHOR).asString,
                    createdDate = json.get(KEY_CREATED).asString,
                    modifiedDate = json.get(KEY_MODIFIED).asString,
                    color = json.get(KEY_COLOR)?.takeUnless { it.isJsonNull }?.asInt
                        ?: CommentCreationMetadata.DEFAULT_POPUP_COLOR,
                    priority = CommentPriority.fromWireValue(
                        optionalString(json, KEY_PRIORITY),
                    ),
                    location = json.get(KEY_LOCATION)
                        ?.takeIf { it.isJsonObject }
                        ?.asJsonObject
                        ?.let(CommentLocation::fromJson),
                )
                OP_ADD_PDF_HIGHLIGHT_COMMENT -> AddPdfMarkupComment(
                    subtype = json.get(KEY_SUBTYPE)?.asInt ?: com.github.barteksc.pdfviewer.PDFView.PdfComment.SUBTYPE_HIGHLIGHT,
                    page = page,
                    needsAction = json.get("needs_action")?.takeUnless { it.isJsonNull }?.asBoolean ?: false,
                    name = json.get(KEY_NAME).asString,
                    rects = rectsFromJson(json.getAsJsonArray(KEY_RECTS)),
                    color = json.get(KEY_COLOR).asInt,
                    contents = json.get(KEY_CONTENTS).asString,
                    author = json.get(KEY_AUTHOR).asString,
                    createdDate = json.get(KEY_CREATED).asString,
                    modifiedDate = json.get(KEY_MODIFIED).asString,
                    selectedText = optionalString(json, KEY_SELECTED_TEXT).orEmpty(),
                    fragments = fragmentsFromJson(
                        json,
                        page,
                        KEY_RECTS,
                        KEY_SELECTED_TEXT,
                    ),
                    priority = CommentPriority.fromWireValue(
                        optionalString(json, KEY_PRIORITY),
                    ),
                    location = json.get(KEY_LOCATION)
                        ?.takeIf { it.isJsonObject }
                        ?.asJsonObject
                        ?.let(CommentLocation::fromJson),
                )
                OP_ADD_PDF_REPLY -> AddPdfReply(
                    page = page,
                    name = json.get(KEY_NAME).asString,
                    parentName = json.get(KEY_PARENT_NAME).asString,
                    parentAnnotationIndex = json.get(KEY_PARENT_ANNOTATION_INDEX).asInt,
                    parentSubtype = json.get(KEY_PARENT_SUBTYPE).asInt,
                    rect = rectFromJson(json.getAsJsonArray(KEY_RECT)),
                    contents = json.get(KEY_CONTENTS).asString,
                    author = json.get(KEY_AUTHOR).asString,
                    createdDate = json.get(KEY_CREATED).asString,
                    modifiedDate = json.get(KEY_MODIFIED).asString,
                )
                OP_SET_PDF_COMMENT_CONTENTS -> SetPdfCommentContents(
                    page = page,
                    annotationIndex = json.get(KEY_ANNOTATION_INDEX).asInt,
                    subtype = json.get(KEY_SUBTYPE).asInt,
                    name = json.get(KEY_NAME).asString,
                    contents = json.get(KEY_CONTENTS).asString,
                    modifiedDate = json.get(KEY_MODIFIED).asString,
                )
                OP_SET_PDF_COMMENT_METADATA -> SetPdfCommentMetadata(
                    page = page,
                    needsAction = json.get("needs_action")?.takeUnless { it.isJsonNull }?.asBoolean,
                    annotationIndex = json.get(KEY_ANNOTATION_INDEX).asInt,
                    subtype = json.get(KEY_SUBTYPE).asInt,
                    name = json.get(KEY_NAME).asString,
                    priority = CommentPriority.fromWireValue(
                        optionalString(json, KEY_PRIORITY),
                    ),
                    location = json.get(KEY_LOCATION)
                        ?.takeIf { it.isJsonObject }
                        ?.asJsonObject
                        ?.let(CommentLocation::fromJson),
                    modifiedDate = json.get(KEY_MODIFIED).asString,
                    color = json.get(KEY_COLOR)?.takeUnless { it.isJsonNull }?.asInt,
                    rect = json.get(KEY_RECT)
                        ?.takeIf { it.isJsonArray }
                        ?.asJsonArray
                        ?.let(::rectFromJson),
                )
                OP_DELETE_PDF_COMMENT -> DeletePdfComment(
                    page = page,
                    annotationIndex = json.get(KEY_ANNOTATION_INDEX).asInt,
                    subtype = json.get(KEY_SUBTYPE).asInt,
                    name = json.get(KEY_NAME).asString,
                )
                OP_SET_FIELD_TEXT -> SetFieldText(
                    page = page,
                    fieldIndex = json.get(KEY_FIELD_INDEX).asInt,
                    fieldName = json.get(KEY_FIELD_NAME).asString,
                    text = json.get(KEY_TEXT).asString,
                )
                OP_SET_FIELD_CHECKED -> SetFieldChecked(
                    page = page,
                    fieldIndex = json.get(KEY_FIELD_INDEX).asInt,
                    fieldName = json.get(KEY_FIELD_NAME).asString,
                    checked = json.get(KEY_CHECKED).asBoolean,
                )
                OP_ADD_SIGNATURE -> AddSignature(
                    page = page,
                    rect = rectFromJson(json.getAsJsonArray(KEY_RECT)),
                    strokes = strokesFromJson(json.getAsJsonArray(KEY_STROKES)),
                    color = json.get(KEY_COLOR).asInt,
                    strokeWidth = json.get(KEY_STROKE_WIDTH).asFloat,
                )
                else -> null
            }
        }.getOrNull()

        private fun optionalString(json: JsonObject, key: String): String? {
            val element = json.get(key) ?: return null
            return if (element.isJsonNull) null else element.asString
        }

        private fun rectsToJson(rects: List<RectF>): JsonArray {
            val array = JsonArray()
            for (rect in rects) {
                val values = JsonArray()
                values.add(rect.left)
                values.add(rect.top)
                values.add(rect.right)
                values.add(rect.bottom)
                array.add(values)
            }
            return array
        }

        private fun rectsFromJson(array: JsonArray): List<RectF> {
            return array.map { element ->
                val values = element.asJsonArray
                RectF(
                    values.get(0).asFloat,
                    values.get(1).asFloat,
                    values.get(2).asFloat,
                    values.get(3).asFloat,
                )
            }
        }

        private fun fragmentsToJson(fragments: List<AnnotationFragment>): JsonArray {
            val array = JsonArray()
            fragments.forEach { fragment ->
                array.add(JsonObject().apply {
                    addProperty(KEY_PAGE, fragment.page)
                    add(KEY_RECTS, rectsToJson(fragment.rects))
                    addProperty(KEY_SELECTED_TEXT, fragment.selectedText)
                })
            }
            return array
        }

        private fun fragmentsFromJson(
            json: JsonObject,
            fallbackPage: Int,
            fallbackRectsKey: String,
            fallbackTextKey: String,
        ): List<AnnotationFragment> {
            val fragments = json.get(KEY_FRAGMENTS)
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.mapNotNull { element ->
                    element.takeIf { it.isJsonObject }?.asJsonObject?.let { fragment ->
                        val rects = fragment.get(KEY_RECTS)
                            ?.takeIf { it.isJsonArray }
                            ?.asJsonArray
                            ?.let(::rectsFromJson)
                            .orEmpty()
                        if (rects.isEmpty()) {
                            null
                        } else {
                            AnnotationFragment(
                                page = fragment.get(KEY_PAGE)?.asInt ?: fallbackPage,
                                rects = rects,
                                selectedText = optionalString(
                                    fragment,
                                    KEY_SELECTED_TEXT,
                                ).orEmpty(),
                            )
                        }
                    }
                }
                .orEmpty()
            if (fragments.isNotEmpty()) {
                return fragments
            }
            return listOf(
                AnnotationFragment(
                    page = fallbackPage,
                    rects = rectsFromJson(json.getAsJsonArray(fallbackRectsKey)),
                    selectedText = optionalString(json, fallbackTextKey).orEmpty(),
                )
            )
        }

        private fun rectToJson(rect: RectF): JsonArray {
            val values = JsonArray()
            values.add(rect.left)
            values.add(rect.top)
            values.add(rect.right)
            values.add(rect.bottom)
            return values
        }

        private fun rectFromJson(values: JsonArray): RectF {
            return RectF().apply {
                left = values.get(0).asFloat
                top = values.get(1).asFloat
                right = values.get(2).asFloat
                bottom = values.get(3).asFloat
            }
        }

        private fun strokesToJson(strokes: List<FloatArray>): JsonArray {
            val array = JsonArray()
            for (stroke in strokes) {
                val values = JsonArray()
                for (value in stroke) {
                    values.add(value)
                }
                array.add(values)
            }
            return array
        }

        private fun strokesFromJson(array: JsonArray): List<FloatArray> {
            return array.map { element ->
                val values = element.asJsonArray
                FloatArray(values.size()) { index -> values.get(index).asFloat }
            }
        }
    }
}

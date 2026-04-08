package com.halilibo.richtext.commonmark

import com.halilibo.richtext.markdown.ResourceType
import com.halilibo.richtext.markdown.node.AstResourceTag

/**
 * Regex pattern to match <resource ... /> tags with any attributes
 */
internal val RESOURCE_TAG_PATTERN = Regex(
  """<resource\s+([^>]+?)\s*/?>"""
)

/**
 * Regex pattern to parse individual attributes from a tag
 */
internal val ATTRIBUTE_PATTERN = Regex(
  """(\w+)\s*=\s*"([^"]+)""""
)

/**
 * Parses a resource tag from HTML literal.
 * Returns AstResourceTag if the literal matches the pattern and contains required attributes.
 * Supports any attribute order and is extensible for future attributes.
 */
internal fun parseResourceTag(literal: String): AstResourceTag? {
  val tagMatch = RESOURCE_TAG_PATTERN.find(literal) ?: return null
  val attributesString = tagMatch.groupValues[1]

  // Parse all attributes into a map
  val attributes = ATTRIBUTE_PATTERN.findAll(attributesString)
    .associate { it.groupValues[1] to it.groupValues[2] }

  // Extract required attributes
  val type = attributes["type"] ?: return null
  val uri = attributes["uri"] ?: return null

  return AstResourceTag(resourceType = ResourceType.fromString(type), uri = uri)
}
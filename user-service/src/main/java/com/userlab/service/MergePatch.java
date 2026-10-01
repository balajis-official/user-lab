package com.userlab.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * JSON Merge Patch, RFC 7396. The whole algorithm is these few lines:
 *   - patch is an object -> for each key: null removes the key, anything else is merged recursively
 *   - patch is not an object (string, number, array, ...) -> it replaces the target completely
 * So arrays are never merged item by item. Sending "addresses": [...] replaces the whole list.
 */
public final class MergePatch {
    private MergePatch() {}

    public static JsonNode apply(JsonNode target, JsonNode patch, boolean nullIgnoredBug) {
        if (!patch.isObject()) {
            return patch;
        }
        ObjectNode result = (target != null && target.isObject())
                ? ((ObjectNode) target).deepCopy()
                : ((ObjectNode) patch).objectNode();
        for (Map.Entry<String, JsonNode> e : patch.properties()) {
            String key = e.getKey();
            JsonNode value = e.getValue();
            if (value.isNull()) {
                if (!nullIgnoredBug) {
                    result.remove(key);
                }
            } else {
                result.set(key, apply(result.get(key), value, nullIgnoredBug));
            }
        }
        return result;
    }
}

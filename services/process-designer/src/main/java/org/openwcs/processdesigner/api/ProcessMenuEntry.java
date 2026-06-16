package org.openwcs.processdesigner.api;

/**
 * One entry in the operator process menu / designer process list: a distinct process key with its
 * ACTIVE version (or null if none active yet) and its title/icon.
 */
public record ProcessMenuEntry(
        String processKey,
        Integer activeVersion,
        String title,
        String icon) {
}

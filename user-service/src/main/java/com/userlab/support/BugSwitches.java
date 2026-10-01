package com.userlab.support;

import org.springframework.stereotype.Component;

/**
 * Known bugs you can switch on (test profile only). A good test FAILS when its bug is on.
 * ignoreIfMatch:         PUT/PATCH skip the version check, so a stale write overwrites newer data (lost update).
 * mergePatchNullIgnored: PATCH ignores "field": null instead of clearing the field (breaks RFC 7396).
 */
@Component
public class BugSwitches {
    private volatile boolean ignoreIfMatch;
    private volatile boolean mergePatchNullIgnored;

    public boolean ignoreIfMatch() { return ignoreIfMatch; }
    public boolean mergePatchNullIgnored() { return mergePatchNullIgnored; }

    public void set(boolean ignoreIfMatch, boolean mergePatchNullIgnored) {
        this.ignoreIfMatch = ignoreIfMatch;
        this.mergePatchNullIgnored = mergePatchNullIgnored;
    }
}

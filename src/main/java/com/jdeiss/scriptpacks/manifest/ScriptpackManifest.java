package com.jdeiss.scriptpacks.manifest;

public record ScriptpackManifest(
        String name,
        String displayName,
        String author,
        String repo,
        String description,
        boolean enforceSafe
) {
    public ScriptpackManifest {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("'name' is required and must not be blank");
        }
        if (author == null || author.isBlank()) {
            throw new IllegalArgumentException("'author' is required and must not be blank");
        }
    }
}

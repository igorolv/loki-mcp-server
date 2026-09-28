package ru.it_spectrum.ai.loki.mcp.connection;

import ru.it_spectrum.ai.loki.mcp.error.Errors;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The default export directory and an optional set of allowed roots from the connections file.
 * Without configured roots, the caller may choose any directory the process can write into.
 */
public record ExportRoots(Path defaultDirectory, List<Path> roots) {
    public static final int MAX_ROOTS = 16;

    public ExportRoots {
        if (defaultDirectory == null || !defaultDirectory.isAbsolute() || roots == null || roots.size() > MAX_ROOTS
                || roots.stream().anyMatch(r -> r == null || !r.isAbsolute())) throw Errors.configuration();
        defaultDirectory = defaultDirectory.normalize();
        roots = roots.stream().map(Path::normalize).toList();
    }

    /**
     * Blank means the default directory; relative paths resolve under it. Configured roots restrict absolute and
     * relative destinations after normalization and symbolic link resolution.
     */
    public Path resolve(String directory) {
        Path target;
        try {
            target = directory == null || directory.isBlank() ? defaultDirectory : Path.of(directory.strip());
        } catch (InvalidPathException e) {
            throw Errors.invalid("Invalid export directory.");
        }
        if (!target.isAbsolute()) target = defaultDirectory.resolve(target);
        target = target.toAbsolutePath().normalize();
        Path root = null;
        if (!roots.isEmpty()) {
            Path candidate = target;
            root = roots.stream().filter(candidate::startsWith).findFirst().orElseThrow(this::outside);
        }
        try {
            Files.createDirectories(target);
            if (root != null && !target.toRealPath().startsWith(root.toRealPath())) throw outside();
        } catch (IOException e) {
            throw Errors.invalid("Cannot create the directory " + target + ". Check the path and write permissions.");
        }
        return target;
    }

    private RuntimeException outside() {
        return Errors.invalid("directory must be inside one of the export directories: " + list()
                + ". Leave it out to write into the first one, or pass a subdirectory name like \"incident-42\".");
    }

    private String list() {
        return roots.stream().map(Path::toString).collect(Collectors.joining(", "));
    }
}

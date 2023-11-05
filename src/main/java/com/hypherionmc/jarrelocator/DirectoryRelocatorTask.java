package com.hypherionmc.jarrelocator;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;

/**
 * @author HypherionSA
 * Same as {@link JarRelocatorTask}, but works on a directory instead of a jar
 */
final class DirectoryRelocatorTask {

    /**
     * META-INF/*.SF
     * META-INF/*.DSA
     * META-INF/*.RSA
     * META-INF/SIG-*
     *
     * <a href="https://docs.oracle.com/en/java/javase/11/docs/specs/jar/jar.html#signed-jar-file">Specification</a>
     */
    private static final Pattern SIGNATURE_FILE_PATTERN = Pattern.compile("META-INF/(?:[^/]+\\.(?:DSA|RSA|SF)|SIG-[^/]+)");

    /**
     * <a href="https://docs.oracle.com/en/java/javase/11/docs/specs/jar/jar.html#signature-validation">Specification</a>
     */
    private static final Pattern SIGNATURE_PROPERTY_PATTERN = Pattern.compile(".*-Digest");

    private final RelocatingRemapper remapper;
    private final Set<String> resources = new HashSet<>();
    private final File inputDirectory;

    DirectoryRelocatorTask(RelocatingRemapper remapper, File inputDirectory) {
        this.remapper = remapper;
        this.inputDirectory = inputDirectory;
    }

    void processEntries() throws IOException {
        for (File entry : listAllFiles(inputDirectory)) {
            String name = entry.getName();
            if (name.equals("META-INF/INDEX.LIST") || entry.isDirectory()) {
                continue;
            }

            // Signatures will become invalid after remapping, so we delete them to avoid making the output useless
            if (SIGNATURE_FILE_PATTERN.matcher(name).matches()) {
                continue;
            }

            try (InputStream entryIn = Files.newInputStream(entry.toPath())) {
                processEntry(entry, entryIn);
            }
        }
    }

    List<File> listAllFiles(File file) {
        List<File> returnList = new ArrayList<>();

        File[] files = file.listFiles();
        if (files == null)
            return new ArrayList<>();

        for (File f : files) {
            if (f.isDirectory()) {
                returnList.addAll(listAllFiles(f));
                continue;
            }

            returnList.add(f);
        }

        return returnList;
    }

    private void processEntry(File entry, InputStream inputStream) throws IOException {
        String name = entry.getAbsolutePath();
        String mappedName = this.remapper.map(name);

        // ensure the parent directory structure exists for the entry.
        processDirectory(mappedName, true);

        if (name.endsWith(".class")) {
            processClass(name, inputStream);
        } else if (name.equals("META-INF/MANIFEST.MF")) {
            processManifest(name, inputStream);
        } else if (!this.resources.contains(mappedName)) {
            processResource(mappedName, inputStream);
        }
    }

    private void processDirectory(String name, boolean parentsOnly) throws IOException {
        int index = name.lastIndexOf('/');
        if (index != -1) {
            String parentDirectory = name.substring(0, index);
            if (!this.resources.contains(parentDirectory)) {
                processDirectory(parentDirectory, false);
            }
        }

        if (parentsOnly) {
            return;
        }

        // directory entries must end in "/"
        File dir = new File(inputDirectory, name);
        if (!dir.exists())
            dir.mkdirs();
        this.resources.add(name);
    }

    private void processResource(String name, InputStream entryIn) throws IOException {
        File file = new File(name);
        File tmpFile = new File(name + ".tmp");
        FileOutputStream outputStream = new FileOutputStream(tmpFile);

        copy(entryIn, outputStream);
        outputStream.close();
        Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);

        this.resources.add(name);
    }

    private void processClass(String name, InputStream entryIn) throws IOException {
        ClassReader classReader = new ClassReader(entryIn);
        ClassWriter classWriter = new ClassWriter(0);
        RelocatingClassVisitor classVisitor = new RelocatingClassVisitor(classWriter, this.remapper, name);

        try {
            classReader.accept(classVisitor, ClassReader.EXPAND_FRAMES);
        } catch (Throwable e) {
            throw new RuntimeException("Error processing class " + name, e);
        }

        byte[] renamedClass = classWriter.toByteArray();

        // Need to take the .class off for remapping evaluation
        String mappedName = this.remapper.map(name.substring(0, name.indexOf('.')));
        File outName = new File(mappedName + ".class");
        File tmpFile = new File(mappedName + ".tmp");
        FileOutputStream outputStream = new FileOutputStream(tmpFile);

        // Now we put it back on so the class file is written out with the right extension.
        outputStream.write(renamedClass);
        outputStream.close();
        Files.move(tmpFile.toPath(), outName.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private void processManifest(String name, InputStream entryIn) throws IOException {
        Manifest in = new Manifest(entryIn);
        Manifest out = new Manifest();

        out.getMainAttributes().putAll(in.getMainAttributes());

        for (Map.Entry<String, Attributes> entry : in.getEntries().entrySet()) {
            Attributes outAttributes = new Attributes();
            for (Map.Entry<Object, Object> property : entry.getValue().entrySet()) {
                String key = property.getKey().toString();
                if (!SIGNATURE_PROPERTY_PATTERN.matcher(key).matches()) {
                    outAttributes.put(property.getKey(), property.getValue());
                }
            }
            out.getEntries().put(entry.getKey(), outAttributes);
        }

        File outFile = new File(name);
        File tempFile = new File(name + ".tmp");
        FileOutputStream outputStream = new FileOutputStream(tempFile);
        out.write(outputStream);
        outputStream.close();

        Files.move(tempFile.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

        this.resources.add(name);
    }

    private static void copy(InputStream from, OutputStream to) throws IOException {
        byte[] buf = new byte[8192];
        while (true) {
            int n = from.read(buf);
            if (n == -1) {
                break;
            }
            to.write(buf, 0, n);
        }
    }
}

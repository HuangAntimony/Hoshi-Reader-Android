package hoshi.build;

import java.io.*;
import java.nio.file.Files;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.*;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;

/** Build-time only: no inference binaries from the AAR are packaged in the APK. */
public abstract class PrepareSherpaTask extends DefaultTask {
    @javax.inject.Inject protected abstract FileSystemOperations getFileSystemOperations();
    @InputFile public abstract RegularFileProperty getArchive();
    @Input public abstract Property<String> getReleaseBaseUrl();
    @OutputDirectory public abstract DirectoryProperty getOutputDirectory();
    @Internal public abstract DirectoryProperty getAssetsDirectory();

    private static String sha(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static byte[] entry(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) throw new IOException("Missing upstream entry: " + name);
        try (InputStream input = zip.getInputStream(entry)) { return input.readAllBytes(); }
    }
    @TaskAction public void prepare() throws Exception {
        File archive = getArchive().get().getAsFile();
        if (!sha(Files.readAllBytes(archive.toPath())).equals("633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"))
            throw new IOException("The sherpa-onnx AAR differs from the pinned official release");
        File root = getOutputDirectory().get().getAsFile();
        getFileSystemOperations().delete(spec -> spec.delete(root));
        File assets = new File(root, "assets");
        File distribution = new File(root, "distribution");
        Files.createDirectories(assets.toPath()); Files.createDirectories(distribution.toPath());
        try (ZipFile aar = new ZipFile(archive)) {
            try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(entry(aar, "classes.jar")));
                 ZipOutputStream output = new ZipOutputStream(new FileOutputStream(new File(root, "bindings.jar")))) {
                ZipEntry source;
                while ((source = input.getNextEntry()) != null) {
                    byte[] bytes = input.readAllBytes();
                    if (source.getName().endsWith(".class")) bytes = SherpaBindings.redirectLoads(bytes);
                    ZipEntry target = new ZipEntry(source.getName()); target.setTime(0);
                    output.putNextEntry(target); output.write(bytes); output.closeEntry();
                }
            }
            var platforms = new ArrayList<String>();
            for (String abi : List.of("arm64-v8a", "armeabi-v7a", "x86_64")) {
                var files = new ArrayList<String>();
                for (String library : List.of("libonnxruntime.so", "libsherpa-onnx-jni.so")) {
                    byte[] bytes = entry(aar, "jni/" + abi + "/" + library);
                    String hash = sha(bytes);
                    // Content-addressed filenames also let upgraded apps reuse the same verified cache.
                    String name = abi + "-" + hash + "-" + library;
                    Files.write(new File(distribution, name).toPath(), bytes);
                    files.add("{\"name\":\"" + name + "\",\"url\":\"" + getReleaseBaseUrl().get() + "/" + name +
                        "\",\"bytes\":" + bytes.length + ",\"sha256\":\"" + hash + "\"}");
                }
                platforms.add("\"" + abi + "\":[" + String.join(",", files) + "]");
            }
            Files.writeString(new File(assets, "transcription-runtime.json").toPath(), "{" + String.join(",", platforms) + "}\n");
        }
    }
}

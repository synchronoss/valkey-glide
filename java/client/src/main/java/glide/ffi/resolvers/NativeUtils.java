/** Copyright Valkey GLIDE Project Contributors - SPDX Identifier: Apache-2.0 */
package glide.ffi.resolvers;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.Locale;
import java.util.logging.Level;

/**
 * A modified version of {@code NativeUtils} from the valkey-glide project. This utility class
 * facilitates loading native libraries packaged within JAR archives.
 *
 * <p>This version of {@code NativeUtils} assumes that the {@code libglide_rs.<ext>} files are named
 * in the format {@code libglide_rs-<os>-<classifier>.<ext>}. For example, instead of {@code
 * libglide_rs.so}, it could be {@code libglide_rs-linux-x86_64.so}.
 *
 * <p>The following runtime libraries are supported for discovery:
 *
 * <ul>
 *   <li>{@code libglide_rs-osx-aarch_64.dylib}
 *   <li>{@code libglide_rs-osx-x86_64.dylib}
 *   <li>{@code libglide_rs-linux-aarch_64.so}
 *   <li>{@code libglide_rs-linux-x86_64.so}
 * </ul>
 *
 * <p>Original sources:
 *
 * <ul>
 *   <li><a
 *       href="https://github.com/valkey-io/valkey-glide/blob/main/java/client/src/main/java/glide/ffi/resolvers/NativeUtils.java">
 *       https://github.com/valkey-io/valkey-glide/blob/main/java/client/src/main/java/glide/ffi/resolvers/NativeUtils.java</a>
 *   <li><a
 *       href="https://raw.githubusercontent.com/adamheinrich/native-utils/master/src/main/java/cz/adamh/utils/NativeUtils.java">
 *       https://raw.githubusercontent.com/adamheinrich/native-utils/master/src/main/java/cz/adamh/utils/NativeUtils.java</a>
 *   <li><a href="https://github.com/adamheinrich/native-utils">
 *       https://github.com/adamheinrich/native-utils</a>
 * </ul>
 */
public final class NativeUtils {

    private static final java.util.logging.Logger logger =
            java.util.logging.Logger.getLogger(NativeUtils.class.getName());

    /**
     * The minimum length a prefix for a file has to have according to {@link
     * File#createTempFile(String, String)}}.
     */
    private static final int MIN_PREFIX_LENGTH = 3;

    /** Temporary directory to store the native runtime when loading. */
    public static final String NATIVE_FOLDER_PATH_PREFIX = "nativeutils";

    /** Temporary directory which will contain the dynamic library files. */
    private static File temporaryDir;

    /** Track if the Glide library has already been loaded */
    private static volatile boolean glideLibLoaded = false;

    /** The native runtime filename for macOS (arm). */
    private static final String LIB_OSX_AARCH_64 = "libglide_rs-osx-aarch_64.dylib";

    /** The native runtime filename for macOS (x86). */
    private static final String LIB_OSX_X86_64 = "libglide_rs-osx-x86_64.dylib";

    /** The native runtime filename for Linux (arm). */
    private static final String LIB_LINUX_AARCH_64 = "libglide_rs-linux-aarch_64.so";

    /** The native runtime filename for Linux (x86). */
    private static final String LIB_LINUX_X86_64 = "libglide_rs-linux-x86_64.so";

    /** Private constructor - this class will never be instanced */
    private NativeUtils() {}

    public static synchronized void loadGlideLib() {
        // Check if already loaded to avoid multiple loads
        if (glideLibLoaded) {
            return;
        }
        try {
            logClassInfo();
            String libName = "/" + determineLibName();
            NativeUtils.loadLibraryFromJar(libName);
            glideLibLoaded = true;
        } catch (java.io.IOException e) {
            e.printStackTrace();
        }
    }

    /** Logs the location that NativeUtils was loaded from. Useful for debugging. */
    private static void logClassInfo() {
        Class<?> clazz = NativeUtils.class;
        URL location = clazz.getProtectionDomain().getCodeSource().getLocation();
        log(
                Level.FINE,
                String.format("Using NativeUtils class: %s from %s", clazz.getName(), location));
    }

    /**
     * Loads library from current the classpath.
     *
     * <p>The file from JAR is copied into system temporary directory and then loaded. The temporary
     * file is deleted after exiting. Method uses String as filename because the pathname is
     * "abstract", not system-dependent.
     *
     * @param path The path of file inside JAR as absolute path (beginning with '/'), e.g.
     *     /package/File.ext
     * @throws IOException If temporary file creation or read/write operation fails
     * @throws IllegalArgumentException If source file (param path) does not exist
     * @throws IllegalArgumentException If the path is not absolute or if the filename is shorter than
     *     <code>MIN_PREFIX_LENGTH</code> (restriction of {@link File#createTempFile(java.lang.String,
     *     java.lang.String)}).
     * @throws FileNotFoundException If the file could not be found on the classpath.
     */
    public static void loadLibraryFromJar(String path) throws IOException {

        if (null == path || !path.startsWith("/")) {
            throw new IllegalArgumentException("The path has to be absolute (start with '/').");
        }

        // Obtain filename from path
        String[] parts = path.split("/");
        String filename = (parts.length > 1) ? parts[parts.length - 1] : null;

        // Check if the filename is okay
        if (filename == null || filename.length() < MIN_PREFIX_LENGTH) {
            throw new IllegalArgumentException(
                    "The filename has to be at least " + MIN_PREFIX_LENGTH + " characters long.");
        }

        // Prepare temporary file
        if (temporaryDir == null) {
            temporaryDir = createTempDirectory(NATIVE_FOLDER_PATH_PREFIX);
            temporaryDir.deleteOnExit();
        }

        File temp = new File(temporaryDir, filename);

        try (InputStream is = NativeUtils.class.getResourceAsStream(path)) {
            if (is == null) {
                cleanupTempFile(temp);
                throw new FileNotFoundException("File " + path + " was not found inside JAR.");
            }
            Files.copy(is, temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            cleanupTempFile(temp);
            throw e;
        }

        try {
            log(Level.FINE, "Loading native library: " + temp.getName());
            System.load(temp.getAbsolutePath());
            log(Level.INFO, "Successfully loaded native library: " + temp.getName());
        } finally {
            if (isPosixCompliant()) {
                // Assume POSIX compliant file system, can be deleted after loading
                cleanupTempFile(temp);
            } else {
                // Assume non-POSIX, and don't delete until last file descriptor closed
                temp.deleteOnExit();
            }
        }
    }

    private static String determineLibName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean isArm = arch.contains("aarch") || arch.contains("arm");
        final String libName;
        if (os.contains("mac")) {
            libName = isArm ? LIB_OSX_AARCH_64 : LIB_OSX_X86_64;
        } else if (os.contains("linux")) {
            libName = isArm ? LIB_LINUX_AARCH_64 : LIB_LINUX_X86_64;
        } else {
            throw new UnsupportedOperationException(
                    "OS not supported. Glide is only available on Mac OS and Linux systems.");
        }
        log(Level.FINE, "Determined native library name: " + libName);
        return libName;
    }

    private static void cleanupTempFile(File temp) {
        if (!temp.delete() && temp.exists()) {
            temp.deleteOnExit();
        }
    }

    private static boolean isPosixCompliant() {
        try {
            return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        } catch (FileSystemNotFoundException | ProviderNotFoundException | SecurityException e) {
            return false;
        }
    }

    private static File createTempDirectory(String prefix) throws IOException {
        String tempDir = System.getProperty("java.io.tmpdir");
        File generatedDir = new File(tempDir, prefix + System.nanoTime());

        if (!generatedDir.mkdir())
            throw new IOException("Failed to create temp directory " + generatedDir.getName());

        return generatedDir;
    }

    private static void log(Level level, String message) {
        if (logger.isLoggable(level)) {
            logger.log(level, String.format("[NativeUtils] %s", message));
        }
    }
}

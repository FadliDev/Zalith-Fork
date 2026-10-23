package net.kdt.pojavlaunch.utils;

import androidx.annotation.Nullable;

import com.movtery.zalithlauncher.feature.log.Logging;
import com.movtery.zalithlauncher.utils.path.PathManager;
import com.movtery.zalithlauncher.utils.ZHTools;
import com.movtery.zalithlauncher.utils.path.UrlManager;

import net.kdt.pojavlaunch.Tools;

import org.apache.commons.io.IOUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

@SuppressWarnings("IOStreamConstructor")
public class DownloadUtils {
    public static void download(String url, OutputStream os) throws IOException {
        download(new URL(url), os);
    }

    public static void download(URL url, OutputStream os) throws IOException {
        InputStream is = null;
        try {
            HttpURLConnection conn = UrlManager.createHttpConnection(url);
            conn.setDoInput(true);
            conn.connect();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Server returned HTTP " + conn.getResponseCode()
                        + ": " + conn.getResponseMessage());
            }
            is = conn.getInputStream();
            IOUtils.copy(is, os);
        } catch (SocketTimeoutException e) {
            throw new IOException("Download timed out: " + url, e);
        } catch (IOException e) {
            throw new IOException("Unable to download from " + url, e);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception e) {
                    Logging.e("DownloadUtils", Tools.printToString(e));
                }
            }
        }
    }

    public static String downloadString(String url) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        download(url, bos);
        bos.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    public static void downloadFile(String url, File out) throws IOException {
        FileUtils.ensureParentDirectory(out);
        try (FileOutputStream fileOutputStream = new FileOutputStream(out)) {
            download(url, fileOutputStream);
        }
    }

    public static void downloadFileMonitored(String urlInput, File outputFile, @Nullable byte[] buffer,
                                             Tools.DownloaderFeedback monitor) throws IOException {
        FileUtils.ensureParentDirectory(outputFile);

        HttpURLConnection conn = null;
        InputStream readStr = null;
        long overall = 0;
        try {
            // Android's HTTP stack can occasionally reuse a stale HTTP/1.1 connection,
            // which may result in "ProtocolException: unexpected end of stream".
            // Always use a fresh connection for Minecraft downloads.
            conn = (HttpURLConnection) new URL(urlInput).openConnection();
            conn.setConnectTimeout(UrlManager.TIME_OUT.getFirst());
            conn.setReadTimeout(UrlManager.TIME_OUT.getFirst());
            conn.setUseCaches(false);
            conn.setRequestProperty("Connection", "close");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.connect();

            int responseCode = conn.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("HTTP " + responseCode + " " + conn.getResponseMessage());
            }

            int length = conn.getContentLength();
            readStr = conn.getInputStream();
            if (buffer == null) buffer = new byte[65535];

            // Write from scratch. If the stream is cut short, the incomplete file is
            // deleted below and ensureSha1() will automatically retry the download.
            try (FileOutputStream fos = new FileOutputStream(outputFile, false)) {
                int current;
                while ((current = readStr.read(buffer)) != -1) {
                    if (current == 0) continue;
                    overall += current;
                    fos.write(buffer, 0, current);
                    monitor.updateProgress(overall, length);
                }
                fos.flush();
            }

            if (length >= 0 && overall != length) {
                throw new IOException("Incomplete download: received " + overall
                        + " of " + length + " bytes from " + urlInput);
            }
        } catch (SocketTimeoutException e) {
            throw new IOException("Download timed out: " + urlInput, e);
        } finally {
            if (readStr != null) {
                try {
                    readStr.close();
                } catch (IOException ignored) {
                }
            }
            if (conn != null) conn.disconnect();
        }
    }

    public static <T> T downloadStringCached(String url, String cacheName, boolean force, ParseCallback<T> parseCallback) throws IOException, ParseException{
        File cacheDestination = new File(PathManager.DIR_CACHE_STRING, cacheName);
        if (force && cacheDestination.exists()) org.apache.commons.io.FileUtils.deleteQuietly(cacheDestination);
        if (cacheDestination.isFile() && cacheDestination.canRead() &&
                ZHTools.getCurrentTimeMillis() < (cacheDestination.lastModified() + 86400000)) {
            try {
                String cachedString = Tools.read(new FileInputStream(cacheDestination));
                return parseCallback.process(cachedString);
            }catch(IOException e) {
                Logging.i("DownloadUtils", "Failed to read the cached file", e);
            }catch (ParseException e) {
                Logging.i("DownloadUtils", "Failed to parse the cached file", e);
            }
        }
        String urlContent = DownloadUtils.downloadString(url);
        // if we download the file and fail parsing it, we will yeet outta there
        // and not cache the unparseable sting. We will return this after trying to save the downloaded
        // string into cache
        T parseResult = parseCallback.process(urlContent);

        boolean tryWriteCache;
        if(cacheDestination.exists()) {
            tryWriteCache = cacheDestination.canWrite();
        } else {
            tryWriteCache = FileUtils.ensureParentDirectorySilently(cacheDestination);
        }

        if(tryWriteCache) try {
            Tools.write(cacheDestination.getAbsolutePath(), urlContent);
        }catch(IOException e) {
            Logging.i("DownloadUtils", "Failed to cache the string", e);
        }
        return parseResult;
    }

    private static <T> T downloadFile(Callable<T> downloadFunction) throws IOException{
        try {
            return downloadFunction.call();
        } catch (IOException e){
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean verifyFile(File file, String sha1) {
        return file.exists() && Tools.compareSHA1(file, sha1);
    }

    public static <T> T ensureSha1(File outputFile, @Nullable String sha1, Callable<T> downloadFunction) throws IOException {
        // Skip if needed
        if(sha1 == null) {
            // If the file exists and we don't know it's SHA1, don't try to redownload it.
            if(outputFile.exists()) return null;
            else return downloadFile(downloadFunction);
        }

        final int maxAttempts = 7;
        boolean fileOkay = verifyFile(outputFile, sha1);
        T result = null;
        IOException lastError = null;

        for (int attempt = 1; attempt <= maxAttempts && !fileOkay; attempt++) {
            try {
                // A failed HTTP stream can leave a partial file. Remove it before the
                // next attempt so the next download always starts from a clean state.
                if (outputFile.exists() && !outputFile.delete()) {
                    Logging.w("DownloadUtils", "Could not delete failed download: " + outputFile);
                }

                downloadFile(downloadFunction);
                fileOkay = verifyFile(outputFile, sha1);
                if (!fileOkay) {
                    lastError = new SHA1VerificationException(
                            "SHA1 mismatch after download attempt " + attempt + "/" + maxAttempts);
                }
            } catch (IOException e) {
                // Network errors, including java.net.ProtocolException: unexpected end
                // of stream, are transient and should trigger an automatic retry.
                lastError = e;
                Logging.w("DownloadUtils", "Download attempt " + attempt + "/"
                        + maxAttempts + " failed; retrying", e);
            }

            if (!fileOkay && outputFile.exists() && !outputFile.delete()) {
                Logging.w("DownloadUtils", "Could not delete failed/incomplete download: " + outputFile);
            }

            if (!fileOkay && attempt < maxAttempts) {
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Download retry interrupted", e);
                }
            }
        }

        if(!fileOkay) {
            if (lastError != null) throw lastError;
            throw new SHA1VerificationException("SHA1 verification failed after " + maxAttempts + " download attempts");
        }
        return result;
    }

    /**
     * Get the content length for a given URL.
     * @param url the URL to get the length for
     * @return the length in bytes or -1 if not available
     * @throws IOException if an I/O error occurs.
     */
    public static long getContentLength(String url) throws IOException {
        HttpURLConnection urlConnection = (HttpURLConnection) new URL(url).openConnection();
        urlConnection.setRequestMethod("HEAD");
        urlConnection.setDoInput(false);
        urlConnection.setDoOutput(false);
        urlConnection.connect();
        int responseCode = urlConnection.getResponseCode();
        if(responseCode >= 200 && responseCode <= 299) return urlConnection.getContentLength();
        return -1;
    }

    public interface ParseCallback<T> {
        T process(String input) throws ParseException;
    }
    public static class ParseException extends Exception {
        public ParseException(Exception e) {
            super(e);
        }
    }

    public static class SHA1VerificationException extends IOException {
        public SHA1VerificationException(String message) {
            super(message);
        }
    }
}


package com.youkeda.project.wechatproject.agent.speech;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Audio format conversion helper.
 */
public class AudioConverter {

    private static final Logger log = LoggerFactory.getLogger(AudioConverter.class);

    private final String ffmpegPath;

    public AudioConverter(String ffmpegPath) {
        this.ffmpegPath = (ffmpegPath == null || ffmpegPath.isBlank()) ? "ffmpeg" : ffmpegPath.trim();
    }

    /**
     * Convert SILK audio into 16kHz mono PCM WAV.
     */
    public byte[] silkToWav(byte[] silkBytes) throws IOException {
        Path silkFile = null;
        Path wavFile = null;
        try {
            silkFile = Files.createTempFile("voice_", ".silk");
            wavFile = Files.createTempFile("voice_", ".wav");
            Files.write(silkFile, silkBytes);

            ProcessBuilder pb = new ProcessBuilder(
                    ffmpegPath,
                    "-y",
                    "-f", "silk",
                    "-i", silkFile.toString(),
                    "-ar", "16000",
                    "-ac", "1",
                    "-f", "wav",
                    wavFile.toString()
            );
            pb.redirectErrorStream(true);

            Process process = pb.start();
            ByteArrayOutputStream ffmpegOutput = new ByteArrayOutputStream();
            try (InputStream is = process.getInputStream()) {
                is.transferTo(ffmpegOutput);
            }
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                String errMsg = ffmpegOutput.toString(StandardCharsets.UTF_8);
                log.error("FFmpeg exited with code {}: {}", exitCode, errMsg);
                throw new IOException("audio conversion failed (exit=" + exitCode + "): " + errMsg);
            }

            byte[] wavBytes = Files.readAllBytes(wavFile);
            log.info("SILK to WAV converted: {}B -> {}B", silkBytes.length, wavBytes.length);
            return wavBytes;

        } catch (InvalidPathException e) {
            throw new IOException("FFmpeg path is invalid: " + ffmpegPath, e);
        } catch (IOException e) {
            if (isMissingCommand(e)) {
                throw new IOException("FFmpeg not found. Install FFmpeg or set agent.speech.stt.ffmpeg-path to the executable path.", e);
            }
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("audio conversion interrupted", e);
        } finally {
            if (silkFile != null) {
                try {
                    Files.deleteIfExists(silkFile);
                } catch (IOException ignored) {
                }
            }
            if (wavFile != null) {
                try {
                    Files.deleteIfExists(wavFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Convert WAV audio into MP3.
     */
    public byte[] wavToMp3(byte[] wavBytes) throws IOException {
        Path wavFile = null;
        Path mp3File = null;
        try {
            wavFile = Files.createTempFile("tts_", ".wav");
            mp3File = Files.createTempFile("tts_", ".mp3");
            Files.write(wavFile, wavBytes);

            ProcessBuilder pb = new ProcessBuilder(
                    ffmpegPath,
                    "-y",
                    "-i", wavFile.toString(),
                    "-codec:a", "libmp3lame",
                    "-b:a", "128k",
                    mp3File.toString()
            );
            pb.redirectErrorStream(true);

            Process process = pb.start();
            ByteArrayOutputStream ffmpegOutput = new ByteArrayOutputStream();
            try (InputStream is = process.getInputStream()) {
                is.transferTo(ffmpegOutput);
            }
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                String errMsg = ffmpegOutput.toString(StandardCharsets.UTF_8);
                log.error("FFmpeg exited with code {}: {}", exitCode, errMsg);
                throw new IOException("audio conversion failed (exit=" + exitCode + "): " + errMsg);
            }

            byte[] mp3Bytes = Files.readAllBytes(mp3File);
            log.info("WAV to MP3 converted: {}B -> {}B", wavBytes.length, mp3Bytes.length);
            return mp3Bytes;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("audio conversion interrupted", e);
        } finally {
            if (wavFile != null) {
                try {
                    Files.deleteIfExists(wavFile);
                } catch (IOException ignored) {
                }
            }
            if (mp3File != null) {
                try {
                    Files.deleteIfExists(mp3File);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Convert any audio format to 16kHz mono PCM WAV via FFmpeg.
     */
    public byte[] toWav(byte[] audioBytes) throws IOException {
        Path srcFile = null;
        Path wavFile = null;
        try {
            srcFile = Files.createTempFile("conv_", ".audio");
            wavFile = Files.createTempFile("conv_", ".wav");
            Files.write(srcFile, audioBytes);

            ProcessBuilder pb = new ProcessBuilder(
                    ffmpegPath,
                    "-y",
                    "-i", srcFile.toString(),
                    "-ar", "16000",
                    "-ac", "1",
                    "-f", "wav",
                    wavFile.toString()
            );
            pb.redirectErrorStream(true);

            Process process = pb.start();
            ByteArrayOutputStream ffmpegOutput = new ByteArrayOutputStream();
            try (InputStream is = process.getInputStream()) {
                is.transferTo(ffmpegOutput);
            }
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                String errMsg = ffmpegOutput.toString(StandardCharsets.UTF_8);
                log.error("FFmpeg exited with code {}: {}", exitCode, errMsg);
                throw new IOException("audio conversion failed (exit=" + exitCode + "): " + errMsg);
            }

            byte[] wavBytes = Files.readAllBytes(wavFile);
            log.info("Audio to WAV converted: {}B -> {}B", audioBytes.length, wavBytes.length);
            return wavBytes;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("audio conversion interrupted", e);
        } finally {
            if (srcFile != null) {
                try {
                    Files.deleteIfExists(srcFile);
                } catch (IOException ignored) {
                }
            }
            if (wavFile != null) {
                try {
                    Files.deleteIfExists(wavFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static boolean isMissingCommand(IOException e) {
        String message = e.getMessage();
        return message != null
                && (message.contains("CreateProcess error=2")
                || message.contains("Cannot run program")
                || message.contains("No such file or directory"));
    }
}

package com.goodatlas.audiorecord;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder.AudioSource;
import android.util.Base64;
import android.util.Log;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.Arguments;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class RNAudioRecordModule extends ReactContextBaseJavaModule {

    private final String TAG = "RNAudioRecord";
    private final ReactApplicationContext reactContext;
    private DeviceEventManagerModule.RCTDeviceEventEmitter eventEmitter;

    private int sampleRateInHz;
    private int channelConfig;
    private int audioFormat;
    private int audioSource;

    private AudioRecord recorder;
    private int bufferSize;

    private String outFile;
    volatile private Promise stopRecordingPromise;

    public RNAudioRecordModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
    }

    @Override
    public String getName() {
        return "RNAudioRecord";
    }

    @ReactMethod
    public void init(ReadableMap options) {
        sampleRateInHz = 44100;
        if (options.hasKey("sampleRate")) {
            sampleRateInHz = options.getInt("sampleRate");
        }

        channelConfig = AudioFormat.CHANNEL_IN_MONO;
        if (options.hasKey("channels")) {
            if (options.getInt("channels") == 2) {
                channelConfig = AudioFormat.CHANNEL_IN_STEREO;
            }
        }

        audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        if (options.hasKey("bitsPerSample")) {
            if (options.getInt("bitsPerSample") == 8) {
                audioFormat = AudioFormat.ENCODING_PCM_8BIT;
            }
        }

        audioSource = AudioSource.VOICE_RECOGNITION;
        if (options.hasKey("audioSource")) {
            audioSource = options.getInt("audioSource");
        }

        String documentDirectoryPath = getReactApplicationContext().getFilesDir().getAbsolutePath();
        outFile = documentDirectoryPath + "/" + "audio.wav";
        if (options.hasKey("wavFile")) {
            String fileName = options.getString("wavFile");
            outFile = documentDirectoryPath + "/" + fileName;
        }

        stopRecordingPromise = null;
        eventEmitter = reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class);

        bufferSize = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);
        int recordingBufferSize = bufferSize * 3;
        recorder = new AudioRecord(audioSource, sampleRateInHz, channelConfig, audioFormat, recordingBufferSize);
    }

    @ReactMethod
    public void start() {
        setPromise(null);

        recorder.startRecording();
        Log.d(TAG, "started recording");

        Thread recordingThread = new Thread(new Runnable() {
            public void run() {
                File tmpFile = null;

                try {
                    int bytesRead;
                    int bytesCount = 0;
                    int count = 0;
                    //String base64Data;
                    byte[] buffer = new byte[bufferSize];

                    tmpFile = File.createTempFile("audio", ".pcm");
                    tmpFile.deleteOnExit();

                    FileOutputStream os = new FileOutputStream(tmpFile);

                    int bytesPerSample = (audioFormat == AudioFormat.ENCODING_PCM_16BIT ? 2 : 1);

                    // Metering is only enabled for 16-bit, mono audio. If we ever want to support
                    // other modes, we need to update our metering algorithm.
                    boolean meteringEnabled = (
                            audioFormat == AudioFormat.ENCODING_PCM_16BIT && channelConfig == AudioFormat.CHANNEL_IN_MONO
                    );

                    while (stopRecordingPromise == null) {
                        bytesRead = recorder.read(buffer, 0, buffer.length);
                        // skip first 2 buffers to eliminate "click sound"
                        if (bytesRead > 0 && ++count > 2) {
                            bytesCount += bytesRead;

                            // Commenting out data event because we don't need it
                            //base64Data = Base64.encodeToString(buffer, Base64.NO_WRAP);
                            //eventEmitter.emit("data", base64Data);

                            if (meteringEnabled) {
                                WritableMap meteringEvent = createMeteringEvent(buffer, bytesRead);
                                meteringEvent.putDouble("currentPosition", ((double)bytesCount / bytesPerSample) / recorder.getSampleRate());
                                eventEmitter.emit("metering", meteringEvent);
                            }

                            convertToLittleEndian(buffer, bytesRead);
                            os.write(buffer, 0, bytesRead);
                        }
                    }

                    recorder.stop();
                    os.close();
                    saveAsWav(tmpFile);

                    int sampleCount = bytesCount / (bytesPerSample * recorder.getChannelCount());

                    WritableMap promiseResult = Arguments.createMap();
                    promiseResult.putString("filePath", "file://" + outFile);
                    promiseResult.putInt("sampleRate", recorder.getSampleRate());
                    promiseResult.putInt("sampleCount", sampleCount);
                    promiseResult.putDouble("duration", ((double)bytesCount / bytesPerSample) / recorder.getSampleRate());

                    getPromise().resolve(promiseResult);
                } catch (Exception e) {
                    try {
                        Promise promise = getPromise();
                        promise.reject(e);
                    } catch (InterruptedException ignored) {
                    }
                } finally {
                    if (tmpFile != null) {
                        tmpFile.delete();
                    }
                }
            }
        });

        recordingThread.start();
    }

    @ReactMethod
    public void stop(Promise promise) {
        setPromise(promise);
    }

    synchronized private void setPromise(Promise promise) {
        stopRecordingPromise = promise;
        notifyAll();
    }

    synchronized private Promise getPromise() throws InterruptedException {
        while (stopRecordingPromise == null) {
            wait();
        }
        return stopRecordingPromise;
    }

    private static WritableMap createMeteringEvent(byte[] byteArray, int bytesRead) {
        ByteBuffer buffer = ByteBuffer.wrap(byteArray);
        buffer.order(ByteOrder.nativeOrder());

        int numSamples = bytesRead / 2; // each sample is 2 bytes (16 bits)

        double sum = 0;
        double maxSample = 0;
        for (int i = 0; i < numSamples; i++) {
            short sample = buffer.getShort(); // read the next 16-bit sample
            sum += sample * sample; // accumulate the sum of squared samples

            // update the maximum sample value
            double sampleAbs = Math.abs((double) sample / Short.MAX_VALUE);
            if (sampleAbs > maxSample) {
                maxSample = sampleAbs;
            }
        }

        double rms = Math.sqrt(sum / numSamples); // compute the root-mean-square value
        double avgdB = 20 * Math.log10(rms / Short.MAX_VALUE);
        double peakdB = 20 * Math.log10(maxSample);

        WritableMap map = Arguments.createMap();

        map.putDouble("average", avgdB);
        map.putDouble("peak", peakdB);

	// Alias to compatibility with react-native-audio-recorder-player
        map.putDouble("currentMetering", avgdB);

        return map;
    }

    public static void convertToLittleEndian(byte[] bytes, int byteCount) {
        if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
            // If the native byte order is already little endian, do nothing
            return;
        }
        for (int i = 0; i < byteCount; i += 2) {
            byte temp = bytes[i];
            bytes[i] = bytes[i + 1];
            bytes[i + 1] = temp;
        }
    }

    private void saveAsWav(File pcmFile) throws java.io.IOException {
        FileInputStream in = new FileInputStream(pcmFile);
        FileOutputStream out = new FileOutputStream(outFile);
        long totalAudioLen = in.getChannel().size();
        long totalDataLen = totalAudioLen + 36;

        addWavHeader(out, totalAudioLen, totalDataLen);

        byte[] data = new byte[bufferSize];
        int bytesRead;
        while ((bytesRead = in.read(data)) != -1) {
            out.write(data, 0, bytesRead);
        }
        Log.d(TAG, "file path:" + outFile);
        Log.d(TAG, "file size:" + out.getChannel().size());

        in.close();
        out.close();
    }

    private void addWavHeader(FileOutputStream out, long totalAudioLen, long totalDataLen)
            throws java.io.IOException {

        long sampleRate = sampleRateInHz;
        int channels = channelConfig == AudioFormat.CHANNEL_IN_MONO ? 1 : 2;
        int bitsPerSample = audioFormat == AudioFormat.ENCODING_PCM_8BIT ? 8 : 16;
        long byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;

        byte[] header = new byte[44];

        header[0] = 'R'; // RIFF chunk
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff); // how big is the rest of this file
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W'; // WAVE chunk
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';
        header[12] = 'f'; // 'fmt ' chunk
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';
        header[16] = 16; // 4 bytes: size of 'fmt ' chunk
        header[17] = 0;
        header[18] = 0;
        header[19] = 0;
        header[20] = 1; // format = 1 for PCM
        header[21] = 0;
        header[22] = (byte) channels; // mono or stereo
        header[23] = 0;
        header[24] = (byte) (sampleRate & 0xff); // samples per second
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff); // bytes per second
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) blockAlign; // bytes in one sample, for all channels
        header[33] = 0;
        header[34] = (byte) bitsPerSample; // bits in a sample
        header[35] = 0;
        header[36] = 'd'; // beginning of the data chunk
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';
        header[40] = (byte) (totalAudioLen & 0xff); // how big is this data chunk
        header[41] = (byte) ((totalAudioLen >> 8) & 0xff);
        header[42] = (byte) ((totalAudioLen >> 16) & 0xff);
        header[43] = (byte) ((totalAudioLen >> 24) & 0xff);

        out.write(header, 0, 44);
    }
}


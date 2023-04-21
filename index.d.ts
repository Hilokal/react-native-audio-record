declare module "react-native-audio-record" {
  export type AudioRecordFinished = {
    filePath: string;
    duration: number;
    sampleCount: number;
    sampleRate: number;
  };

  export type AudioRecordMeteringEvent = {
    currentMetering: number;
    currentPosition: number;
    average: number;
    peak: number;
  };

  export interface IAudioRecord {
    init: (options: Options) => void;
    start: () => void;
    stop: () => Promise<AudioRecordFinished>;
    on: (event: "metering", callback: (data: AudioRecordMeteringEvent) => void) => void;
    removeListener: (event: EventOptions) => void;
  }

  export type EventOptions = "metering";

  export interface Options {
    sampleRate: number;
    /**
     * - `1 | 2`
     */
    channels: number;
    /**
     * - `8 | 16`
     */
    bitsPerSample: number;
    /**
     * - `6`
     */
    audioSource?: number;
    wavFile: string;
  }

  const AudioRecord: IAudioRecord;

  export default AudioRecord;
}

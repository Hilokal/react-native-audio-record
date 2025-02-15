import { NativeModules, NativeEventEmitter } from 'react-native';
const { RNAudioRecord } = NativeModules;
const EventEmitter = new NativeEventEmitter(RNAudioRecord);

const AudioRecord = {};

AudioRecord.init = options => RNAudioRecord.init(options);
AudioRecord.start = () => RNAudioRecord.start();
AudioRecord.stop = () => RNAudioRecord.stop();
AudioRecord.cleanupFiles = () => RNAudioRecord.cleanupFiles();
AudioRecord.isAvailable = () => { return NativeModules.RNAudioRecord != null; };

const eventsMap = {
  data: 'data',
  metering: 'metering',
};

AudioRecord.on = (event, callback) => {
  const nativeEvent = eventsMap[event];

  if (!nativeEvent) {
    throw new Error("Invalid event");
  }

  EventEmitter.removeAllListeners(nativeEvent);

  return EventEmitter.addListener(nativeEvent, callback);
};

AudioRecord.removeListener = (event) => {
  const nativeEvent = eventsMap[event];

  if (!nativeEvent) {
    throw new Error("Invalid event");
  }

  return EventEmitter.removeAllListeners(nativeEvent);
};

export default AudioRecord;


import { DeviceEventEmitter, NativeModules, NativeEventEmitter, Platform } from 'react-native';
const { RNAudioRecord } = NativeModules;

const AudioRecord = {};

AudioRecord.init = options => RNAudioRecord.init(options);
AudioRecord.start = () => RNAudioRecord.start();
AudioRecord.stop = () => RNAudioRecord.stop();

const eventsMap = {
  data: 'data',
  metering: 'metering',
};

AudioRecord.on = (event, callback) => {
  const nativeEvent = eventsMap[event];

  if (!nativeEvent) {
    throw new Error('Invalid event');
  }

  if (Platform.OS === 'android') {
    const EventEmitter = DeviceEventEmitter.addListener(nativeEvent, callback);
    return EventEmitter;
  } else {
    const EventEmitter = new NativeEventEmitter(RNAudioRecord);
    EventEmitter.removeAllListeners(nativeEvent);
    return EventEmitter.addListener(nativeEvent, callback);
  }
};

export default AudioRecord;

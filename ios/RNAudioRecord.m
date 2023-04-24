#import "RNAudioRecord.h"

@implementation RNAudioRecord

RCT_EXPORT_MODULE();

NSURL * FilesDirectory(void) {
    NSURL *tempDirectoryURL = [NSURL fileURLWithPath:NSTemporaryDirectory()];
    NSURL *myTempDirURL = [tempDirectoryURL URLByAppendingPathComponent:@"wav-audio"];

    return myTempDirURL;
}

bool hasListeners;

RCT_EXPORT_METHOD(init:(NSDictionary *) options) {
    RCTLogInfo(@"init");
    _recordState.mDataFormat.mSampleRate        = options[@"sampleRate"] == nil ? 44100 : [options[@"sampleRate"] doubleValue];
    _recordState.mDataFormat.mBitsPerChannel    = options[@"bitsPerSample"] == nil ? 16 : [options[@"bitsPerSample"] unsignedIntValue];
    _recordState.mDataFormat.mChannelsPerFrame  = options[@"channels"] == nil ? 1 : [options[@"channels"] unsignedIntValue];
    _recordState.mDataFormat.mBytesPerPacket    = (_recordState.mDataFormat.mBitsPerChannel / 8) * _recordState.mDataFormat.mChannelsPerFrame;
    _recordState.mDataFormat.mBytesPerFrame     = _recordState.mDataFormat.mBytesPerPacket;
    _recordState.mDataFormat.mFramesPerPacket   = 1;
    _recordState.mDataFormat.mReserved          = 0;
    _recordState.mDataFormat.mFormatID          = kAudioFormatLinearPCM;
    _recordState.mDataFormat.mFormatFlags       = _recordState.mDataFormat.mBitsPerChannel == 8 ? kLinearPCMFormatFlagIsPacked : (kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked);

    
    _recordState.bufferByteSize = 2048;
    _recordState.mSelf = self;
    
    NSString *fileName = options[@"wavFile"] == nil ? @"audio.wav" : options[@"wavFile"];

    _fileURL = [FilesDirectory() URLByAppendingPathComponent: fileName];
}

RCT_EXPORT_METHOD(start: (RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {

    RCTLogInfo(@"start");

    if (_recordState.mIsRunning) {
        reject(@"start_failed", @"start() called but recorder already running", nil);
        return;
    }

    // most audio players set session category to "Playback", record won't work in this mode
    // therefore set session category to "Record" before recording
    [[AVAudioSession sharedInstance] setCategory:AVAudioSessionCategoryPlayAndRecord error:nil];

    _recordState.mIsRunning = true;
    _recordState.mCurrentPacket = 0;
    
    NSError *error = nil;
    if (![[NSFileManager defaultManager] createDirectoryAtURL:FilesDirectory() withIntermediateDirectories:YES attributes:nil error:&error]) {
        reject(@"start_failed", @"createDirectoryAtPath failed", error);
        return;
    }

    CFURLRef cfURL = CFBridgingRetain(_fileURL);
    if (cfURL == NULL) {
        reject(@"start_failed", @"Converstion to CFURL failed", nil);
        return;
    }

    AudioFileCreateWithURL(cfURL, kAudioFileWAVEType, &_recordState.mDataFormat, kAudioFileFlags_EraseFile, &_recordState.mAudioFile);
    CFRelease(cfURL);

    RCTLogInfo(@"Start recording to file: %@", _fileURL);
    
    OSStatus status = AudioQueueNewInput(&_recordState.mDataFormat, HandleInputBuffer, &_recordState, NULL, NULL, 0, &_recordState.mQueue);
    if (status != noErr) {
        NSError *error = [NSError errorWithDomain:NSOSStatusErrorDomain code:status userInfo:nil];
        reject(@"osstatus_error", @"AudioQueueNewInput failed", error);
        return;
    }

    for (int i = 0; i < kNumberBuffers; i++) {
        AudioQueueAllocateBuffer(_recordState.mQueue, _recordState.bufferByteSize, &_recordState.mBuffers[i]);
        AudioQueueEnqueueBuffer(_recordState.mQueue, _recordState.mBuffers[i], 0, NULL);
    }

    // Enable level metering
    UInt32 enabled = true;
    status = AudioQueueSetProperty(_recordState.mQueue, kAudioQueueProperty_EnableLevelMetering, &enabled, sizeof(enabled));
    if (status != noErr) {
        RCTLogWarn(@"AudioQueueSetProperty(kAudioQueueProperty_EnableLevelMetering) failed");
    }

    status = AudioQueueStart(_recordState.mQueue, NULL);
    if (status != noErr) {
        NSError *error = [NSError errorWithDomain:NSOSStatusErrorDomain code:status userInfo:nil];
        reject(@"osstatus_error", @"AudioQueueStart failed", error);
        return;
    }

    resolve(@YES);
}

RCT_EXPORT_METHOD(stop:(RCTPromiseResolveBlock)resolve
                  rejecter:(__unused RCTPromiseRejectBlock)reject) {
    RCTLogInfo(@"stop");

    if (!_recordState.mIsRunning) {
        reject(@"stop_failed", @"stop() called but recorder not running", nil);
        return;
    }

    AudioQueueStop(_recordState.mQueue, true);
    AudioQueueDispose(_recordState.mQueue, true);
    AudioFileClose(_recordState.mAudioFile);

    _recordState.mIsRunning = false;

    NSDictionary *eventData = @{
      @"filePath": [_fileURL absoluteString],
      @"sampleCount": @(_recordState.mCurrentPacket),
      @"sampleRate": @(_recordState.mDataFormat.mSampleRate),
      @"duration": @((double) _recordState.mCurrentPacket / _recordState.mDataFormat.mSampleRate)
    };

    resolve(eventData);
}

RCT_EXPORT_METHOD(cleanupFiles: (RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    NSError *error = nil;
    if (![[NSFileManager defaultManager] removeItemAtURL:FilesDirectory() error:&error]) {
        reject(@"cleanupFiles", @"removeItemAtURL failed", error);
    } else {
        resolve(@YES);
    }
}

void HandleInputBuffer(void *inUserData,
                       AudioQueueRef inAQ,
                       AudioQueueBufferRef inBuffer,
                       const AudioTimeStamp *inStartTime,
                       UInt32 inNumPackets,
                       const AudioStreamPacketDescription *inPacketDesc) {
    AQRecordState* pRecordState = (AQRecordState *)inUserData;
    
    if (!pRecordState->mIsRunning) {
        return;
    }
    
    if (AudioFileWritePackets(pRecordState->mAudioFile,
                              false,
                              inBuffer->mAudioDataByteSize,
                              inPacketDesc,
                              pRecordState->mCurrentPacket,
                              &inNumPackets,
                              inBuffer->mAudioData
                              ) == noErr) {
        pRecordState->mCurrentPacket += inNumPackets;
    }
    
    // Commenting out the data event. It seems wasteful to send all this over
    // javascript bridge when we don't need to use it

    //short *samples = (short *) inBuffer->mAudioData;
    //long nsamples = inBuffer->mAudioDataByteSize;

    //NSData *data = [NSData dataWithBytes:samples length:nsamples];
    //NSString *str = [data base64EncodedStringWithOptions:0];
    //[pRecordState->mSelf sendEventWithName:@"data" body:str];

    if (hasListeners) {
        UInt32 dataSize = sizeof(AudioQueueLevelMeterState) * 10;
        AudioQueueLevelMeterState *levels = (AudioQueueLevelMeterState*)malloc(dataSize);

        OSStatus rc = AudioQueueGetProperty(inAQ, kAudioQueueProperty_CurrentLevelMeterDB, levels, &dataSize);
        if (rc == noErr) {
            double currentPosition = 0;
            if (inStartTime != NULL) {
                currentPosition = inStartTime->mSampleTime / pRecordState->mDataFormat.mSampleRate;
            }

            NSDictionary *eventData = @{
                @"currentPosition": @(currentPosition),
                @"currentMetering": @(levels[0].mAveragePower),
                @"average": @(levels[0].mAveragePower),
                @"peak": @(levels[0].mPeakPower)
            };

            [pRecordState->mSelf sendEventWithName:@"metering" body:eventData];
        }

        free(levels);
    }
    
    AudioQueueEnqueueBuffer(pRecordState->mQueue, inBuffer, 0, NULL);
}

- (NSArray<NSString *> *)supportedEvents {
    return @[@"data", @"metering"];
}

- (void)dealloc {
    RCTLogInfo(@"dealloc");
    AudioQueueDispose(_recordState.mQueue, true);
}

-(void)startObserving {
    hasListeners = YES;
}

-(void)stopObserving {
    hasListeners = NO;
}

@end

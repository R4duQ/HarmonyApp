// Harmony-Bridging-Header.h: the Objective-C++ bridges Swift can see.
// Swift never includes C++ headers; it talks to the shared engines only
// through these Objective-C interfaces (implemented in .mm files).

#import "HarmonyEngineCheck.h"
#import "../Audio/HarmonyDspBridge.h"
#import "../Scanner/FastAcousticScanner_iOS.h"

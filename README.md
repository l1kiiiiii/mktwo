MKTwo - Mantra Recognition App
Overview
MKTwo is an Android application that allows users to record and recognize mantras using audio processing. It extracts Mel-Frequency Cepstral Coefficients (MFCCs) from audio input and compares them using Dynamic Time Warping (DTW) to detect mantra matches. The app features a user-friendly interface with a dropdown (Spinner) for selecting mantras, buttons for recording, listening, and deleting mantras, and real-time match counting.
Features

Mantra Selection: Choose from a list of saved mantras via a dropdown (Spinner) with a "Select a Mantra" placeholder.
Recording: Record new mantras, saved as WAV files with automatic name conflict resolution.
Real-Time Recognition: Detect mantra recitations using MFCC and DTW, with a configurable match limit and audio alert.
Delete Mantras: Remove user-recorded mantras (except the inbuilt testhello mantra).
Native Audio Processing: Uses a C++ library (mantra_matcher) for efficient MFCC extraction and DTW computation.

Prerequisites

Android Studio: Version 2022.3.1 or later (e.g., Chipmunk, Dolphin).
NDK: Android Native Development Kit (version 23.1 or later) for compiling the C++ library.
Device/Emulator: Android device or emulator with API 21 (Lollipop) or higher, with microphone support.
Permissions: Microphone access (RECORD_AUDIO) required for recording and recognition.

Setup Instructions
1. Clone the Repository
git clone <repository-url>
cd mktwo

2. Configure Project

Open the project in Android Studio.
Ensure the following files are present:
app/src/main/java/com/example/mktwo/MainActivity.kt: Main app logic.
app/src/main/cpp/mantra_matcher.cpp: Native C++ library for MFCC and DTW.
app/src/main/res/layout/activity_main.xml: UI layout.
app/src/main/res/values/strings.xml: String resources.
app/src/main/assets/testhello.wav: Inbuilt mantra audio file.



3. Configure Gradle

Project-level build.gradle:
buildscript {
    ext.kotlin_version = '2.0.20' // Or '2.2.0' for break/continue in inline lambdas
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath 'com.android.tools.build:gradle:8.1.0'
        classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin_version"
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}


Module-level build.gradle (app):
apply plugin: 'com.android.application'
apply plugin: 'kotlin-android'

android {
    compileSdkVersion 33
    defaultConfig {
        applicationId "com.example.mktwo"
        minSdkVersion 21
        targetSdkVersion 33
        versionCode 1
        versionName "1.0"
        externalNativeBuild {
            cmake {
                cppFlags "-std=c++11"
            }
        }
    }
    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
    externalNativeBuild {
        cmake {
            path "src/main/cpp/CMakeLists.txt"
        }
    }
    kotlinOptions {
        jvmTarget = '17'
    }
}

dependencies {
    implementation "org.jetbrains.kotlin:kotlin-stdlib:$kotlin_version"
    implementation 'androidx.core:core-ktx:1.10.0'
    implementation 'androidx.appcompat:appcompat:1.6.1'
}



4. Configure Native Build (CMake)
Create or update app/src/main/cpp/CMakeLists.txt:
cmake_minimum_required(VERSION 3.10.2)
project(mantra_matcher)
add_library(mantra_matcher SHARED mantra_matcher.cpp)
find_library(log-lib log)
target_link_libraries(mantra_matcher ${log-lib})

5. Sync and Build

In Android Studio, click File > Sync Project with Gradle Files.
Build the project: Build > Rebuild Project.
Ensure the NDK is installed (via SDK Manager) and the mantra_matcher library compiles correctly.

6. Add Inbuilt Mantra

Place testhello.wav in app/src/main/assets/. This is the default mantra copied to storage on first run.

Usage

Launch the App:

Grant microphone permission when prompted.
The Spinner displays available mantras, including testhello and user-recorded mantras, with a "Select a Mantra" placeholder.


Select a Mantra:

Choose a mantra from the Spinner. The Start and Delete buttons enable only for valid selections (excluding testhello for deletion).


Record a Mantra:

Enter a name in the text field and click Record.
Click Stop Recording to save the mantra as a WAV file.
The new mantra is auto-selected in the Spinner.


Start Listening:

Enter a match limit (e.g., 3) and click Start Listening.
The app detects mantra recitations and updates the match count.
An alert sounds when the match limit is reached.


Delete a Mantra:

Select a user-recorded mantra and click Delete.
Confirm the deletion in the dialog. The Spinner resets to the placeholder.



Project Structure

app/src/main/java/com/example/mktwo/:
MainActivity.kt: Core app logic, UI handling, and JNI calls to mantra_matcher.


app/src/main/cpp/:
mantra_matcher.cpp: C++ implementation of MFCC extraction and DTW computation.


app/src/main/res/:
layout/activity_main.xml: UI layout with Spinner, buttons, and text views.
values/strings.xml: String resources, including select_mantra_hint.


app/src/main/assets/:
testhello.wav: Inbuilt mantra audio file.



Technical Details

Audio Processing:
Sample rate: 48kHz, mono, 16-bit PCM.
MFCC extraction: 13 coefficients, 2048-sample frames, 40 mel filterbanks.
DTW: Uses cosine similarity for frame comparison, normalized score (0 to 1).


Native Library:
Implements FFT (Cooley-Tukey radix-2), mel filterbanks, DCT, and DTW without external dependencies.
Uses C++11 with <complex>, <vector>, <cmath>, <algorithm>.


Kotlin:
Uses Kotlin 2.0.20 (or 2.2.0 for break/continue in inline lambdas).
Manages audio recording with AudioRecord and UI with View Binding.



Known Issues

Ensure testhello.wav is a valid WAV file (48kHz, mono, 16-bit PCM).
If using Kotlin 2.2 for inline lambda break/continue, update kotlin_version to 2.2.0 and add -Xlanguage-version=2.2 to kotlinOptions.
Microphone access may be blocked by system settings; check AppOpsManager in isMicrophoneOpAllowed.

Contributing

Submit pull requests with clear descriptions.
Report issues via the repository’s issue tracker.

# Source list shared by the Android build (CMakeLists.txt) and the host test (hosttest/).
set(HIONEBEAT_DIR ${CMAKE_CURRENT_LIST_DIR})
set(HIONEBEAT_SOURCES
        ${HIONEBEAT_DIR}/hione_beat_offline.cpp
        ${HIONEBEAT_DIR}/hione_beat_live.cpp
        ${HIONEBEAT_DIR}/shim/samplerate_shim.c
        ${HIONEBEAT_DIR}/btrack/BTrack.cpp
        ${HIONEBEAT_DIR}/btrack/OnsetDetectionFunction.cpp
        ${HIONEBEAT_DIR}/qm-dsp/dsp/onsets/DetectionFunction.cpp
        ${HIONEBEAT_DIR}/qm-dsp/dsp/phasevocoder/PhaseVocoder.cpp
        ${HIONEBEAT_DIR}/qm-dsp/dsp/transforms/FFT.cpp
        ${HIONEBEAT_DIR}/qm-dsp/dsp/tempotracking/TempoTrackV2.cpp
        ${HIONEBEAT_DIR}/qm-dsp/maths/MathUtilities.cpp
        ${HIONEBEAT_DIR}/qm-dsp/ext/kissfft/kiss_fft.c
        ${HIONEBEAT_DIR}/qm-dsp/ext/kissfft/tools/kiss_fftr.c)
set(HIONEBEAT_INCLUDES
        ${HIONEBEAT_DIR}
        ${HIONEBEAT_DIR}/shim
        ${HIONEBEAT_DIR}/btrack
        ${HIONEBEAT_DIR}/qm-dsp
        ${HIONEBEAT_DIR}/qm-dsp/ext/kissfft
        ${HIONEBEAT_DIR}/qm-dsp/ext/kissfft/tools)
# One kissfft build serves both QM-DSP and BTrack, in double precision as QM-DSP expects.
set(HIONEBEAT_DEFINES kiss_fft_scalar=double USE_KISS_FFT NDEBUG)

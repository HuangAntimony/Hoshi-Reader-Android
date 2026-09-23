Synthetic 3-second, 700 Hz tones; no speech or copyrighted recordings.
Generated with FFmpeg for native decoder/seek/gapless regression tests:

ffmpeg -f lavfi -i 'sine=frequency=700:sample_rate=44100:duration=3' -c:a aac -b:a 48k tone.m4a
ffmpeg -f lavfi -i 'sine=frequency=700:sample_rate=44100:duration=3' -c:a libmp3lame tone.mp3
ffmpeg -f lavfi -i 'sine=frequency=700:sample_rate=48000:duration=3' -c:a libopus tone.opus

Two audio tracks: first = 3 s at 700 Hz; second = 6 s at 900 Hz, marked default.
ffmpeg -i tone.m4a -f lavfi -i 'sine=frequency=900:sample_rate=44100:duration=6' -map 0:a -map 1:a -c:a:0 copy -c:a:1 aac -b:a:1 48k -disposition:a:0 0 -disposition:a:1 default two-tracks.m4a

package com.hione.player.api

/**
 * Oki Hione Cyberpunk Web Remote & TV Big-Screen Stage Application.
 * 
 * Embedded zero-dependency HTML5/CSS3/JavaScript SPA served directly by [HioneApiServer].
 * Provides a Spotify-Connect facsimile for phones, tablets, laptops, and TV sound systems.
 */
object HioneWebRemoteHtml {

    fun getRemoteHtml(isTvMode: Boolean = false): String {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <meta name="theme-color" content="#030d14">
    <meta name="apple-mobile-web-app-capable" content="yes">
    <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
    <title>Hione Connect · ${if (isTvMode) "TV Stage" else "Remote"}</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Audiowide&family=Rajdhani:wght@500;600;700&display=swap" rel="stylesheet">
    <style>
        :root {
            --hione-cyan: #FFC56B;
            --hione-teal: #FFAB2E;
            --hione-pink: #FF1774;
            --hione-purple: #9D4EDD;
            --bg-dark: #030D14;
            --card-bg: rgba(6, 22, 33, 0.75);
            --card-border: rgba(0, 229, 255, 0.35);
        }
        * {
            box-sizing: border-box;
            margin: 0;
            padding: 0;
            user-select: none;
            -webkit-user-select: none;
        }
        body {
            background-color: var(--bg-dark);
            color: #ffffff;
            font-family: 'Rajdhani', sans-serif;
            min-height: 100vh;
            display: flex;
            flex-direction: column;
            align-items: center;
            overflow-x: hidden;
            background-image: 
                radial-gradient(circle at 50% 10%, rgba(57, 197, 187, 0.15), transparent 50%),
                radial-gradient(circle at 80% 90%, rgba(255, 23, 116, 0.12), transparent 45%),
                linear-gradient(180deg, #02080D 0%, #030D14 50%, #010406 100%);
        }
        .header {
            width: 100%;
            max-width: ${if (isTvMode) "1200px" else "520px"};
            padding: 16px 20px;
            display: flex;
            justify-content: space-between;
            align-items: center;
            border-bottom: 1px solid rgba(0, 229, 255, 0.15);
            backdrop-filter: blur(12px);
            z-index: 10;
        }
        .brand {
            display: flex;
            align-items: center;
            gap: 10px;
        }
        .brand-logo {
            width: 28px;
            height: 28px;
            border-radius: 50%;
            background: linear-gradient(135deg, var(--hione-teal), var(--hione-pink));
            box-shadow: 0 0 12px var(--hione-cyan);
        }
        .brand-title {
            font-family: 'Audiowide', cursive;
            font-size: 18px;
            letter-spacing: 1.5px;
            background: linear-gradient(90deg, #FFFFFF, var(--hione-cyan));
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
        }
        .device-badge {
            display: flex;
            align-items: center;
            gap: 6px;
            background: rgba(0, 229, 255, 0.12);
            border: 1px solid var(--card-border);
            padding: 4px 10px;
            border-radius: 12px;
            font-size: 12px;
            font-weight: 700;
            color: var(--hione-cyan);
            font-family: 'Audiowide', cursive;
        }
        .device-dot {
            width: 7px;
            height: 7px;
            border-radius: 50%;
            background-color: #00FF88;
            box-shadow: 0 0 8px #00FF88;
            animation: pulse 2s infinite;
        }
        @keyframes pulse {
            0%, 100% { opacity: 1; transform: scale(1); }
            50% { opacity: 0.4; transform: scale(0.85); }
        }
        .container {
            width: 100%;
            max-width: ${if (isTvMode) "1200px" else "520px"};
            padding: 20px;
            display: flex;
            flex-direction: ${if (isTvMode) "row" else "column"};
            align-items: center;
            gap: ${if (isTvMode) "50px" else "20px"};
            flex: 1;
            justify-content: center;
        }
        .art-wrapper {
            position: relative;
            width: ${if (isTvMode) "420px" else "280px"};
            height: ${if (isTvMode) "420px" else "280px"};
            display: flex;
            justify-content: center;
            align-items: center;
        }
        .art-glow {
            position: absolute;
            width: 100%;
            height: 100%;
            border-radius: 20px;
            background: radial-gradient(circle, rgba(0, 229, 255, 0.4), transparent 70%);
            filter: blur(25px);
            z-index: 1;
            transition: all 0.5s ease;
        }
        .art-img {
            position: relative;
            width: 100%;
            height: 100%;
            border-radius: 16px;
            object-fit: cover;
            border: 2px solid var(--card-border);
            box-shadow: 0 10px 30px rgba(0, 0, 0, 0.8);
            z-index: 2;
            background-color: #05141c;
        }
        .details-pane {
            width: 100%;
            display: flex;
            flex-direction: column;
            align-items: ${if (isTvMode) "flex-start" else "center"};
            text-align: ${if (isTvMode) "left" else "center"};
        }
        .track-title {
            font-family: 'Audiowide', cursive;
            font-size: ${if (isTvMode) "36px" else "22px"};
            font-weight: 700;
            color: #FFFFFF;
            margin-bottom: 6px;
            line-height: 1.2;
            max-width: 100%;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }
        .track-artist {
            font-size: ${if (isTvMode) "24px" else "16px"};
            font-weight: 700;
            color: var(--hione-teal);
            margin-bottom: 4px;
        }
        .track-album {
            font-size: ${if (isTvMode) "16px" else "13px"};
            color: rgba(255, 255, 255, 0.6);
            margin-bottom: 12px;
        }
        .badge-row {
            display: flex;
            gap: 8px;
            margin-bottom: 18px;
        }
        .tech-badge {
            background: rgba(0, 229, 255, 0.1);
            border: 1px solid rgba(0, 229, 255, 0.3);
            border-radius: 6px;
            padding: 3px 8px;
            font-size: 11px;
            font-family: 'Audiowide', cursive;
            color: #FFFFFF;
        }
        .tech-badge.hires {
            border-color: var(--hione-pink);
            background: rgba(255, 23, 116, 0.15);
            color: var(--hione-pink);
        }
        .progress-bar-container {
            width: 100%;
            display: flex;
            flex-direction: column;
            gap: 6px;
            margin-bottom: 20px;
        }
        .progress-track {
            width: 100%;
            height: 6px;
            background: rgba(255, 255, 255, 0.15);
            border-radius: 3px;
            position: relative;
            cursor: pointer;
        }
        .progress-fill {
            height: 100%;
            width: 0%;
            background: linear-gradient(90deg, var(--hione-cyan), var(--hione-pink));
            border-radius: 3px;
            position: relative;
        }
        .progress-fill::after {
            content: '';
            position: absolute;
            right: -5px;
            top: -4px;
            width: 14px;
            height: 14px;
            background: #FFFFFF;
            border-radius: 50%;
            box-shadow: 0 0 10px var(--hione-cyan);
        }
        .time-row {
            display: flex;
            justify-content: space-between;
            font-size: 13px;
            font-family: 'Audiowide', cursive;
            color: rgba(255, 255, 255, 0.7);
        }
        .controls-row {
            display: flex;
            align-items: center;
            justify-content: center;
            gap: ${if (isTvMode) "30px" else "18px"};
            margin-bottom: 24px;
        }
        .btn-ctrl {
            background: rgba(255, 255, 255, 0.08);
            border: 1px solid rgba(255, 255, 255, 0.2);
            color: #FFFFFF;
            border-radius: 50%;
            width: ${if (isTvMode) "54px" else "44px"};
            height: ${if (isTvMode) "54px" else "44px"};
            display: flex;
            justify-content: center;
            align-items: center;
            font-size: ${if (isTvMode) "22px" else "18px"};
            cursor: pointer;
            transition: all 0.2s ease;
        }
        .btn-ctrl:hover, .btn-ctrl:focus {
            background: rgba(0, 229, 255, 0.25);
            border-color: var(--hione-cyan);
            transform: scale(1.1);
            outline: none;
        }
        .btn-play {
            width: ${if (isTvMode) "76px" else "62px"};
            height: ${if (isTvMode) "76px" else "62px"};
            background: linear-gradient(135deg, var(--hione-teal), var(--hione-pink));
            border: none;
            box-shadow: 0 0 20px rgba(0, 229, 255, 0.5);
            font-size: ${if (isTvMode) "30px" else "24px"};
        }
        .btn-play:hover, .btn-play:focus {
            transform: scale(1.12);
            box-shadow: 0 0 30px var(--hione-pink);
        }
        .volume-row {
            width: 100%;
            display: flex;
            align-items: center;
            gap: 12px;
        }
        .vol-slider {
            flex: 1;
            -webkit-appearance: none;
            height: 6px;
            border-radius: 3px;
            background: rgba(255, 255, 255, 0.15);
            outline: none;
        }
        .vol-slider::-webkit-slider-thumb {
            -webkit-appearance: none;
            width: 16px;
            height: 16px;
            border-radius: 50%;
            background: var(--hione-cyan);
            box-shadow: 0 0 10px var(--hione-cyan);
            cursor: pointer;
        }
        .spectrum-bar-box {
            display: flex;
            gap: 3px;
            height: 32px;
            align-items: flex-end;
            margin-top: 14px;
        }
        .spectrum-col {
            width: 5px;
            background: linear-gradient(0deg, var(--hione-cyan), var(--hione-pink));
            border-radius: 2px;
            height: 4px;
            transition: height 0.1s ease;
        }
        .mode-nav {
            display: flex;
            gap: 10px;
            margin-top: 20px;
        }
        .nav-link {
            text-decoration: none;
            color: rgba(255, 255, 255, 0.7);
            font-size: 13px;
            padding: 6px 14px;
            border-radius: 20px;
            background: rgba(255, 255, 255, 0.05);
            border: 1px solid rgba(255, 255, 255, 0.15);
            font-family: 'Audiowide', cursive;
        }
        .pair-overlay {
            position: fixed;
            inset: 0;
            background: rgba(1, 6, 10, 0.92);
            display: none;
            align-items: center;
            justify-content: center;
            z-index: 50;
            padding: 16px;
        }
        .pair-overlay.show { display: flex; }
        .pair-card {
            width: 100%;
            max-width: 380px;
            background: #061621;
            border: 1px solid var(--card-border);
            border-radius: 16px;
            padding: 20px;
            text-align: center;
        }
        .pair-card h2 { font-family: 'Audiowide', cursive; font-size: 18px; margin-bottom: 8px; color: var(--hione-cyan); }
        .pair-card p { font-size: 14px; color: rgba(255,255,255,0.75); margin-bottom: 14px; }
        .pair-input {
            width: 100%;
            font-size: 28px;
            letter-spacing: 8px;
            text-align: center;
            padding: 10px;
            border-radius: 10px;
            border: 1px solid var(--card-border);
            background: #02090e;
            color: #fff;
            margin-bottom: 12px;
            user-select: text;
            -webkit-user-select: text;
        }
        .pair-btn {
            width: 100%;
            padding: 12px;
            border-radius: 10px;
            border: none;
            font-size: 16px;
            font-weight: 700;
            color: #fff;
            background: linear-gradient(135deg, var(--hione-teal), var(--hione-pink));
            cursor: pointer;
        }
        .pair-msg { min-height: 18px; margin-top: 10px; font-size: 13px; color: var(--hione-pink); }
        .nav-link.active {
            background: rgba(0, 229, 255, 0.2);
            border-color: var(--hione-cyan);
            color: var(--hione-cyan);
        }
    </style>
</head>
<body>

    <div class="header">
        <div class="brand">
            <div class="brand-logo"></div>
            <div class="brand-title">HIONE CONNECT</div>
        </div>
        <div class="device-badge">
            <div class="device-dot"></div>
            <span>HiBy M500</span>
        </div>
    </div>

    <div class="container">
        <div class="art-wrapper">
            <div class="art-glow" id="artGlow"></div>
            <img src="data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw==" class="art-img" id="artImg" alt="Album Artwork">
        </div>

        <div class="details-pane">
            <div class="track-title" id="trackTitle">Connecting…</div>
            <div class="track-artist" id="trackArtist">—</div>
            <div class="track-album" id="trackAlbum"></div>

            <!-- Badges start empty (em dash): the first /api/v1/status poll fills them with the
                 device's real DAC rate, volume and battery. They used to ship hardcoded
                 "24-BIT / 96kHz DTA", "VOL 85%" and "BAT 100%", which rendered as live readings
                 on every page load before any data existed. -->
            <div class="badge-row">
                <div class="tech-badge" id="rateBadge">—</div>
                <div class="tech-badge" id="volBadge">VOL —</div>
                <div class="tech-badge" id="batteryBadge">BAT —</div>
            </div>

            <div class="progress-bar-container">
                <div class="progress-track" id="progressTrack" onclick="seekClick(event)">
                    <div class="progress-fill" id="progressFill"></div>
                </div>
                <div class="time-row">
                    <span id="timeCurrent">00:00</span>
                    <span id="timeTotal">00:00</span>
                </div>
            </div>

            <div class="controls-row">
                <button class="btn-ctrl" onclick="toggleShuffle()" title="Shuffle" id="btnShuffle">⇄</button>
                <button class="btn-ctrl" onclick="prevTrack()" title="Previous" id="btnPrev">⏮</button>
                <button class="btn-ctrl btn-play" onclick="togglePlay()" title="Play/Pause" id="btnPlay">▶</button>
                <button class="btn-ctrl" onclick="nextTrack()" title="Next" id="btnNext">⏭</button>
                <button class="btn-ctrl" onclick="toggleLike()" title="Heart" id="btnLike" style="color: var(--hione-pink);">♥</button>
            </div>

            <div class="volume-row">
                <span style="font-size: 16px;">🔈</span>
                <input type="range" min="0" max="100" value="0" class="vol-slider" id="volSlider" oninput="setVolume(this.value)">
                <span style="font-size: 16px;">🔊</span>
            </div>

            <div class="spectrum-bar-box" id="spectrumBox"></div>

            <div class="mode-nav">
                <a href="/" class="nav-link ${if (!isTvMode) "active" else ""}">Phone remote</a>
                <a href="/tv" class="nav-link ${if (isTvMode) "active" else ""}">TV</a>
            </div>
        </div>
    </div>

    <audio id="tvAudioElement" preload="auto"></audio>

    <div class="pair-overlay" id="pairOverlay">
        <div class="pair-card">
            <h2>Pair this browser</h2>
            <p>On the M500, open Hione Music Settings, Wi-Fi Remote, and tap Pair a browser. Type the 6-digit code shown there, or scan its QR code.</p>
            <input class="pair-input" id="pairCode" inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="000000">
            <button class="pair-btn" id="pairBtn" onclick="doPair()">Pair</button>
            <div class="pair-msg" id="pairMsg"></div>
        </div>
    </div>

    <script>
        // ---- pairing and auth. The token lives in this browser's localStorage for this
        // address only. Media elements get signed, expiring URLs from /api/v1/status instead.
        const TOKEN_KEY = 'hione_api_token';
        let apiToken = null;
        try { apiToken = localStorage.getItem(TOKEN_KEY); } catch (e) {}
        const pairFromUrl = (location.hash.match(/[#&]pair=(\d{6})/) || [])[1] || '';
        if (location.hash) history.replaceState(null, '', location.pathname + location.search);

        function showPair(msg) {
            document.getElementById('pairOverlay').classList.add('show');
            document.getElementById('pairMsg').innerText = msg || '';
        }
        function hidePair() { document.getElementById('pairOverlay').classList.remove('show'); }
        function dropToken() {
            apiToken = null;
            try { localStorage.removeItem(TOKEN_KEY); } catch (e) {}
        }

        async function doPair(codeArg) {
            const code = (codeArg || document.getElementById('pairCode').value || '').replace(/\D/g, '');
            if (code.length !== 6) { showPair('The code is 6 digits.'); return; }
            document.getElementById('pairBtn').disabled = true;
            try {
                const res = await fetch('/api/v1/pair', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ code: code })
                });
                const j = await res.json().catch(() => ({}));
                if (res.ok && j.token) {
                    apiToken = j.token;
                    try { localStorage.setItem(TOKEN_KEY, apiToken); } catch (e) {}
                    hidePair();
                    currentTrackId = -1;
                    pollStatus();
                    return;
                }
                showPair({
                    'bad-code': 'Wrong code. Check the M500 screen.',
                    'closed': 'Pairing is not open. Tap Pair a browser on the M500 first.',
                    'locked': 'Too many wrong codes. Wait a minute, then open pairing again on the M500.'
                }[j.error] || (res.status === 429 ? 'Too many attempts. Wait a few minutes.' : 'Pairing failed.'));
            } catch (e) {
                showPair('Could not reach the M500.');
            } finally {
                document.getElementById('pairBtn').disabled = false;
            }
        }
        document.getElementById('pairCode').addEventListener('keydown', (e) => { if (e.key === 'Enter') doPair(); });

        async function api(path, opts) {
            if (!apiToken) { showPair(); throw new Error('not paired'); }
            opts = opts || {};
            opts.headers = Object.assign({}, opts.headers || {}, { 'Authorization': 'Bearer ' + apiToken });
            const res = await fetch(path, opts);
            if (res.status === 401) {
                dropToken();
                showPair('This browser is no longer paired. Pair it again.');
                throw new Error('unauthorized');
            }
            return res;
        }

        let isPlaying = false;
        let curDuration = 0;
        let curPosition = 0;
        let lastSyncTime = Date.now();
        let isUserSeeking = false;
        let currentTrackId = -1;
        const isTvMode = ${if (isTvMode) "true" else "false"};
        const tvAudio = document.getElementById('tvAudioElement');
        let tvAudioUnlocked = false;

        function connectTvAudio() {
            if (!tvAudio) return;
            tvAudioUnlocked = true;
            tvAudio.play().then(() => {
                const banner = document.getElementById('tvAudioBanner');
                if (banner) banner.style.display = 'none';
            }).catch(e => {
                console.log("Audio unlock triggered", e);
            });
        }

        // Spectrum bars driven by the device's REAL FFT (/api/v1/levels). When no Visualizer is
        // bound on the device the endpoint reports active=false and the bars stay flat — no
        // Math.random() animation pretending to be audio.
        const specBox = document.getElementById('spectrumBox');
        for (let i = 0; i < 24; i++) {
            const col = document.createElement('div');
            col.className = 'spectrum-col';
            specBox.appendChild(col);
        }
        const specCols = document.querySelectorAll('.spectrum-col');

        async function updateSpectrum() {
            try {
                if (!apiToken) return;
                const res = await api('/api/v1/levels', { cache: 'no-store' });
                if (!res.ok) { specCols.forEach(col => col.style.height = '4px'); return; }
                const lv = await res.json();
                if (!lv.active || !lv.is_playing || !Array.isArray(lv.fft) || lv.fft.length === 0) {
                    specCols.forEach(col => col.style.height = '4px');
                    return;
                }
                const per = Math.max(1, Math.floor(lv.fft.length / specCols.length));
                specCols.forEach((col, i) => {
                    let s = 0;
                    for (let k = 0; k < per; k++) s += (lv.fft[i * per + k] || 0);
                    const mag = Math.min(1, s / per);
                    col.style.height = (4 + Math.round(mag * 28)) + 'px';
                });
            } catch (e) {
                specCols.forEach(col => col.style.height = '4px');
            }
        }
        setInterval(updateSpectrum, 150);

        async function pollStatus() {
            try {
                if (!apiToken) return;
                const res = await api('/api/v1/status', { cache: 'no-store' });
                if (!res.ok) return;
                const data = await res.json();
                renderState(data);
            } catch (e) {
                console.error("Poll error", e);
            }
        }

        function renderState(data) {
            if (!data || !data.track) return;

            document.getElementById('trackTitle').innerText = data.track.title || "No Track";
            document.getElementById('trackArtist').innerText = data.track.artist || "Unknown Artist";
            document.getElementById('trackAlbum').innerText = data.track.album || "";

            // Rate badge: measured DAC rate only. No rate reported (0) = em dash, never the old
            // 'Direct DTA' label, which claimed a direct route without having measured one. The
            // 'hires' highlight is applied only when the measured rate actually is hi-res.
            const dacRate = data.hardware.dac_sample_rate_hz || 0;
            const rateBadge = document.getElementById('rateBadge');
            rateBadge.innerText = dacRate > 0 ? (dacRate / 1000).toFixed(1) + ' kHz DTA' : '—';
            rateBadge.classList.toggle('hires', dacRate >= 88200);
            const volPct = data.playback.volume_pct;
            const batPct = data.hardware.battery_pct;
            document.getElementById('volBadge').innerText = (typeof volPct === 'number' && volPct >= 0) ? 'VOL ' + volPct + '%' : 'VOL —';
            document.getElementById('batteryBadge').innerText = (typeof batPct === 'number' && batPct >= 0) ? 'BAT ' + batPct + '%' : 'BAT —';

            isPlaying = data.playback.is_playing;
            document.getElementById('btnPlay').innerText = isPlaying ? '⏸' : '▶';

            curDuration = data.playback.duration_ms || 0;
            curPosition = data.playback.position_ms || 0;
            lastSyncTime = Date.now();

            document.getElementById('timeTotal').innerText = formatTime(curDuration);
            document.getElementById('volSlider').value = data.playback.volume_pct;

            // Refresh artwork if changed
            if (data.track.id !== currentTrackId) {
                currentTrackId = data.track.id;
                const art = document.getElementById('artImg');
                const urls = data.urls || {};
                if (urls.artwork) art.src = urls.artwork;

                // Sync TV Sound System Audio Stream
                if (isTvMode && tvAudio && currentTrackId > 0 && urls.stream) {
                    tvAudio.src = urls.stream;
                    if (data.playback.position_ms > 0) {
                        tvAudio.currentTime = data.playback.position_ms / 1000;
                    }
                    if (isPlaying) {
                        tvAudio.play().catch(_ => {});
                    }
                }
            }

            // Sync live audio state in TV Mode
            if (isTvMode && tvAudio && currentTrackId > 0) {
                const targetSec = (data.playback.position_ms || 0) / 1000;
                if (Math.abs(tvAudio.currentTime - targetSec) > 2.5) {
                    tvAudio.currentTime = targetSec;
                }
                tvAudio.volume = Math.min(1.0, Math.max(0.0, (data.playback.volume_pct || 0) / 100.0));
                if (isPlaying && tvAudio.paused) {
                    tvAudio.play().catch(_ => {});
                } else if (!isPlaying && !tvAudio.paused) {
                    tvAudio.pause();
                }
            }
        }

        function formatTime(ms) {
            if (!ms || ms <= 0) return "00:00";
            const totalSec = Math.floor(ms / 1000);
            const m = Math.floor(totalSec / 60);
            const s = totalSec % 60;
            return (m < 10 ? '0' : '') + m + ':' + (s < 10 ? '0' : '') + s;
        }

        // Live smooth progress ticker
        setInterval(() => {
            if (isPlaying && curDuration > 0) {
                const elapsed = curPosition + (Date.now() - lastSyncTime);
                const pct = Math.min(100, (elapsed / curDuration) * 100);
                document.getElementById('progressFill').style.width = pct + '%';
                document.getElementById('timeCurrent').innerText = formatTime(elapsed);
            }
        }, 200);

        async function sendPost(path, body = {}) {
            try {
                const res = await api(path, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(body)
                });
                const data = await res.json();
                renderState(data);
            } catch (e) {
                console.error("API error", e);
            }
        }

        function togglePlay() { sendPost('/api/v1/playback/toggle'); }
        function nextTrack() { sendPost('/api/v1/playback/next'); }
        function prevTrack() { sendPost('/api/v1/playback/previous'); }
        function setVolume(val) { sendPost('/api/v1/playback/volume', { volume: parseInt(val) }); }
        function toggleShuffle() { sendPost('/api/v1/playback/mode', { shuffle: true }); }
        function toggleLike() { sendPost('/api/v1/playback/like'); }

        function seekClick(e) {
            const rect = document.getElementById('progressTrack').getBoundingClientRect();
            const clickX = e.clientX - rect.left;
            const pct = Math.max(0, Math.min(1, clickX / rect.width));
            const targetMs = Math.floor(pct * curDuration);
            sendPost('/api/v1/playback/seek', { position_ms: targetMs });
        }

        // Keyboard navigation for TV Remote Control
        window.addEventListener('keydown', (e) => {
            switch(e.key) {
                case ' ':
                case 'Enter':
                    togglePlay();
                    break;
                case 'ArrowRight':
                    nextTrack();
                    break;
                case 'ArrowLeft':
                    prevTrack();
                    break;
                case 'ArrowUp':
                    sendPost('/api/v1/playback/volume', { delta: 1 });
                    break;
                case 'ArrowDown':
                    sendPost('/api/v1/playback/volume', { delta: -1 });
                    break;
            }
        });

        // A signed stream URL that expired (long pause) fails here; forget the track so the
        // next status poll hands out a fresh URL.
        if (tvAudio) tvAudio.addEventListener('error', () => { currentTrackId = -1; });

        // Fast poll loop
        if (!apiToken) {
            if (pairFromUrl) { document.getElementById('pairCode').value = pairFromUrl; doPair(pairFromUrl); }
            else showPair();
        }
        pollStatus();
        setInterval(pollStatus, 1500);
    </script>
</body>
</html>
        """.trimIndent()
    }
}

package main

import (
	"embed"
	"flag"
	"fmt"
	"io/fs"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

//go:embed web/* web/css/* web/js/* web/assets/*
var webFS embed.FS

type Config struct {
	Host        string
	Port        int
	RepoDir     string
	FirmwareDir string
	HioneOutDir  string
	ApkDir      string
	NoBrowser   bool
}

var cfg Config

func main() {
	flag.StringVar(&cfg.Host, "host", "0.0.0.0", "Host IP to bind web server")
	flag.IntVar(&cfg.Port, "port", 3939, "Port to listen on (Default: 3939 [Hione])")
	flag.StringVar(&cfg.RepoDir, "repo-dir", "", "Path to m500 git repository root (auto-detected if empty)")
	flag.BoolVar(&cfg.NoBrowser, "no-browser", false, "Do not auto-open browser on startup")
	flag.Parse()

	// Locate repository root
	if cfg.RepoDir == "" {
		cfg.RepoDir = findRepoRoot()
	}
	cfg.FirmwareDir = filepath.Join(cfg.RepoDir, "m500-system-archive", "firmware", "extracted_1.00")
	cfg.HioneOutDir = filepath.Join(cfg.RepoDir, "hioneos", "out")
	cfg.ApkDir = filepath.Join(cfg.RepoDir, "hione-player-kotlin")

	log.Printf("==========================================================")
	log.Printf("   ✨ HioneOS Web Flasher & USB Serial Updater v0.1.0 ✨  ")
	log.Printf("==========================================================")
	log.Printf("Repository Root:  %s", cfg.RepoDir)
	log.Printf("Stock Firmware:   %s", cfg.FirmwareDir)
	log.Printf("HioneOS Images:    %s", cfg.HioneOutDir)
	log.Printf("Core System APKs: %s", cfg.ApkDir)
	log.Printf("----------------------------------------------------------")

	mux := http.NewServeMux()

	// API Routes
	RegisterAPIRoutes(mux)

	// Static Web Assets
	subFS, err := fs.Sub(webFS, "web")
	if err != nil {
		log.Fatalf("Failed to initialize embedded web assets: %v", err)
	}

	// Wrap static file server with custom headers & cache control
	fileServer := http.FileServer(http.FS(subFS))
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Range")
		w.Header().Set("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges")
		w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")

		// If requesting a file that doesn't exist, fallback to index.html for SPA
		path := strings.TrimPrefix(r.URL.Path, "/")
		if path != "" {
			if _, err := subFS.Open(path); err != nil {
				r.URL.Path = "/"
			}
		}
		fileServer.ServeHTTP(w, r)
	})

	serverAddr := fmt.Sprintf("%s:%d", cfg.Host, cfg.Port)
	log.Printf("Server listening at: http://localhost:%d", cfg.Port)
	if cfg.Host == "0.0.0.0" {
		log.Printf("Network access at:   http://%s:%d (Check local IP)", getLocalIP(), cfg.Port)
	}
	log.Printf("Press Ctrl+C to terminate.")
	log.Printf("==========================================================")

	if !cfg.NoBrowser {
		go func() {
			time.Sleep(500 * time.Millisecond)
			openBrowser(fmt.Sprintf("http://localhost:%d", cfg.Port))
		}()
	}

	server := &http.Server{
		Addr:         serverAddr,
		Handler:      mux,
		ReadTimeout:  30 * time.Minute,
		WriteTimeout: 30 * time.Minute,
	}

	if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatalf("Server error: %v", err)
	}
}

func findRepoRoot() string {
	// Try current working directory
	cwd, err := os.Getwd()
	if err == nil {
		if isRepoRoot(cwd) {
			return cwd
		}
		parent := filepath.Dir(cwd)
		if isRepoRoot(parent) {
			return parent
		}
		grandParent := filepath.Dir(parent)
		if isRepoRoot(grandParent) {
			return grandParent
		}
	}
	// Hardcoded fallback for known development workspace
	known := "/home/reaver/Documents/GitHub/m500"
	if isRepoRoot(known) {
		return known
	}
	return "."
}

func isRepoRoot(path string) bool {
	if _, err := os.Stat(filepath.Join(path, "m500-system-archive")); err == nil {
		return true
	}
	return false
}

func getLocalIP() string {
	cmd := exec.Command("hostname", "-I")
	out, err := cmd.Output()
	if err == nil {
		ips := strings.Fields(string(out))
		if len(ips) > 0 {
			return ips[0]
		}
	}
	return "127.0.0.1"
}

func openBrowser(url string) {
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "linux":
		cmd = exec.Command("xdg-open", url)
	case "darwin":
		cmd = exec.Command("open", url)
	case "windows":
		cmd = exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	default:
		return
	}
	_ = cmd.Start()
}

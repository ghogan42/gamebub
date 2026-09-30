#include <iostream>
#include <format>
#include <vector>
#include <cstdio>
#include <cmath>
#include <getopt.h>
#include <optional>

#include <SDL2/SDL.h>

#include "audio.hpp"
#include "common.hpp"
#include "simulator.hpp"
#include "window.hpp"
#include "recording_writer.hpp"
#include "script.hpp"
#include "png.hpp"

JoypadState read_joypad_state() {
    const uint8_t* keyboard = SDL_GetKeyboardState(nullptr);
    JoypadState joypad = {};
    joypad.start = keyboard[SDL_SCANCODE_RETURN];
    joypad.select = keyboard[SDL_SCANCODE_RSHIFT];
    joypad.b = keyboard[SDL_SCANCODE_X];
    joypad.a = keyboard[SDL_SCANCODE_Z];
    joypad.down = keyboard[SDL_SCANCODE_DOWN];
    joypad.up = keyboard[SDL_SCANCODE_UP];
    joypad.left = keyboard[SDL_SCANCODE_LEFT];
    joypad.right = keyboard[SDL_SCANCODE_RIGHT];
    joypad.l = keyboard[SDL_SCANCODE_A];
    joypad.r = keyboard[SDL_SCANCODE_S];
    return joypad;
}

void print_help() {
    std::cout << "Usage: sim"
        << " --bios path/to/bios"
        << " [--script input.txt] [--headless] [--frames N]"
        << " [rom path]\n"
        << "  --script FILE   drive input from a script (see sim/script.hpp)\n"
        << "  --headless      no window or audio; exit when the script ends\n"
        << "  --frames N      exit after N frames\n";
    std::exit(1);
}

int main(int argc, char** argv) {
    std::filesystem::path bios_path;
    std::filesystem::path rom_path;
    std::filesystem::path audio_save_path;
    std::filesystem::path record_path;
    std::filesystem::path script_path;
    bool headless = false;
    long max_frames = -1;

    const char* const short_opts = "b:";
    const option long_opts[] = {
        {"bios-path", required_argument, nullptr, 'b'},
        {"save-audio", required_argument, nullptr, 'a'},
        {"record", required_argument, nullptr, 'r'},
        {"script", required_argument, nullptr, 's'},
        {"headless", no_argument, nullptr, 'H'},
        {"frames", required_argument, nullptr, 'f'},
        {"help", no_argument, nullptr, 'h'},
        {nullptr, no_argument, nullptr, 0}
    };

    while (true) {
        const auto opt = getopt_long(argc, argv, short_opts, long_opts, nullptr);
        if (opt == -1) {
            break;
        }

        switch (opt) {
            case 'b':
                bios_path = optarg;
                break;
            case 'a':
                audio_save_path = optarg;
                break;
            case 'r':
                record_path = optarg;
                break;
            case 's':
                script_path = optarg;
                break;
            case 'H':
                headless = true;
                break;
            case 'f':
                max_frames = std::stol(optarg);
                break;
            case 'h':
            case '?':
            default:
                print_help();
                break;
        }
    }
    if (optind < argc) {
        rom_path = argv[optind++];
    }
    if (rom_path.empty()) {
        print_help();
    }

    // Initialize SDL.
    std::optional<Script> script;
    if (!script_path.empty()) {
        script.emplace();
        if (!script->load(script_path)) {
            return 1;
        }
    }

    std::optional<Window> window;
    std::optional<Audio> audio;
    if (!headless) {
        SDL_Init(SDL_INIT_VIDEO | SDL_INIT_EVENTS | SDL_INIT_AUDIO);
        window.emplace(Simulator::width(), Simulator::height());
        audio.emplace(audio_save_path);
    }

    Simulator simulator(rom_path, bios_path);
    simulator.reset();

    std::optional<RecordingWriter> recording;
    if (!record_path.empty()) {
        recording.emplace(
            record_path,
            Simulator::width(),
            Simulator::height(),
            Simulator::videoFramerate(),
            Simulator::audioSampleHz()
        );
    }

    bool single_step = false;
    bool paused = false;
    uint64_t frame_timer = 0;
    int frame_counter = 0;
    long total_frames = 0;
    uint64_t start_ticks = SDL_GetTicks64();
    while (true) {
        // Run any non-frame script steps (screenshots, etc).
        bool quit = false;
        while (script.has_value() && !script->done()
               && script->current().op != Script::Op::Frames) {
            const Script::Step& step = script->current();
            switch (step.op) {
                case Script::Op::Screenshot: {
                    auto path = script->resolve(step.arg);
                    png::write_bgra(path, simulator.getFramebuffer().renderBuffer().data(),
                                    Simulator::width(), Simulator::height());
                    std::cout << "[frame " << total_frames << "] screenshot " << path.string() << std::endl;
                    break;
                }
                case Script::Op::Sav: {
                    auto path = script->resolve(step.arg);
                    write_file(path, simulator.getBackup());
                    std::cout << "[frame " << total_frames << "] sav " << path.string() << std::endl;
                    break;
                }
                case Script::Op::Dump: {
                    auto path = script->resolve(step.arg);
                    auto data = simulator.readMemory(step.arg2);
                    if (data.empty()) {
                        std::cerr << "ERROR: unknown memory region " << step.arg2 << "\n";
                        return 1;
                    }
                    write_file(path, data);
                    std::cout << "[frame " << total_frames << "] dump " << step.arg2 << " " << path.string() << std::endl;
                    break;
                }
                case Script::Op::Echo:
                    std::cout << "[frame " << total_frames << "] " << step.arg << std::endl;
                    break;
                case Script::Op::Quit:
                    quit = true;
                    break;
                default:
                    break;
            }
            script->advance();
        }
        if (headless && script.has_value() && script->done()) {
            quit = true;
        }
        if (max_frames >= 0 && total_frames >= max_frames) {
            quit = true;
        }
        if (quit) {
            break;
        }

        // Handle SDL events.
        SDL_Event event;
        if (!headless && SDL_PollEvent(&event)) {
            if (event.type == SDL_QUIT) {
                break;
            } else if (event.type == SDL_KEYDOWN) {
                auto key = event.key.keysym;
                bool command = (key.mod & KMOD_GUI) != 0;
                if (key.sym == SDLK_p && command) {
                    paused = !paused;
                } else if (key.sym == SDLK_n && command) {
                    paused = true;
                    single_step = true;
                } else if (key.sym == SDLK_r && command) {
                    std::cout << "Resetting..." << std::endl;
                    simulator.reset();
                }
            }
        }

        // Simulate for a frame.
        if (!paused || single_step) {
            // Simulate
            if (script.has_value() && !script->done()) {
                simulator.set_joypad_state(script->current().buttons);
                script->consume_frame();
            } else if (!headless) {
                simulator.set_joypad_state(read_joypad_state());
            } else {
                simulator.set_joypad_state({});
            }
            simulator.simulate_frame();
            frame_counter++;
            total_frames++;

            if (recording.has_value()) {
                recording->write_video(simulator.getFramebuffer());
                recording->write_audio(simulator.getAudioSampleBuffer());
            }

            // Audio
            std::vector<int16_t>& samples = simulator.getAudioSampleBuffer();
            if (audio.has_value()) {
                audio->push(samples.data(), samples.size());
            }
            samples.clear();
        }
        if (window.has_value()) {
            window->update(simulator.getFramebuffer());
        }
        single_step = false;

        // Update title.
        if (SDL_GetTicks64() - frame_timer >= 1000) {
            char buffer[100];
            snprintf(buffer, 100, "Sim - FPS: %d", frame_counter);
            if (window.has_value()) {
                window->setTitle(buffer);
            } else {
                std::cerr << "[frame " << total_frames << "] " << frame_counter << " fps\n";
            }
            frame_counter = 0;
            frame_timer = SDL_GetTicks64();
        }
    }

    double seconds = (SDL_GetTicks64() - start_ticks) / 1000.0;
    std::cout << "Simulated " << total_frames << " frames in " << seconds << "s" << std::endl;

    // Tear down SDL objects before SDL_Quit.
    audio.reset();
    window.reset();
    SDL_Quit();
}

// Needed for Verilator with some linkers.
double sc_time_stamp() { return 0; }

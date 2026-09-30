#pragma once

#include <cstdio>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

#include "input.hpp"

// Input script for driving the simulator without a keyboard.
//
// One command per line; '#' starts a comment. Buttons: A B L R START SELECT
// UP DOWN LEFT RIGHT, combine with '+', e.g. "L+R".
//
//   wait N                   run N frames with no buttons held
//   press BTN [COUNT]        tap BTN COUNT times (default 1); each tap holds
//                            for 4 frames then releases for 4 frames
//   hold BTN N               hold BTN for N frames, then release
//   screenshot PATH          save the current frame as PNG
//   sav PATH                 write the current backup memory (SRAM etc.)
//   dump REGION PATH         write raw internal memory (GBA: iwram, ewram)
//   echo TEXT                print TEXT to stdout
//   quit                     exit the simulator
//
// Relative paths are resolved against the script's directory unless they
// start with "./" (then they're relative to the working directory).
class Script {
public:
    enum class Op { Frames, Screenshot, Sav, Dump, Echo, Quit };

    struct Step {
        Op op;
        JoypadState buttons = {};
        int frames = 0;
        std::string arg;
        std::string arg2;
    };

    static constexpr int TAP_HOLD = 4;
    static constexpr int TAP_RELEASE = 4;

    bool load(const std::filesystem::path& path) {
        std::ifstream in(path);
        if (!in) {
            std::cerr << "ERROR: cannot open script " << path << "\n";
            return false;
        }
        base_dir = path.parent_path();
        std::string line;
        int line_no = 0;
        while (std::getline(in, line)) {
            line_no++;
            if (!parse_line(line)) {
                std::cerr << "ERROR: " << path << ":" << line_no << ": cannot parse: " << line << "\n";
                return false;
            }
        }
        return true;
    }

    bool done() const { return index >= steps.size(); }
    const Step& current() const { return steps[index]; }

    // Consume one frame of the current Frames step.
    void consume_frame() {
        if (++frame_in_step >= steps[index].frames) {
            advance();
        }
    }
    void advance() {
        index++;
        frame_in_step = 0;
    }

    std::filesystem::path resolve(const std::string& p) const {
        std::filesystem::path path(p);
        if (path.is_absolute() || p.rfind("./", 0) == 0 || p.rfind("../", 0) == 0) {
            return path;
        }
        return base_dir / path;
    }

private:
    std::vector<Step> steps;
    size_t index = 0;
    int frame_in_step = 0;
    std::filesystem::path base_dir;

    static bool parse_buttons(const std::string& spec, JoypadState& state) {
        std::stringstream ss(spec);
        std::string name;
        while (std::getline(ss, name, '+')) {
            for (auto& c : name) c = toupper(c);
            if (name == "A") state.a = true;
            else if (name == "B") state.b = true;
            else if (name == "L") state.l = true;
            else if (name == "R") state.r = true;
            else if (name == "START") state.start = true;
            else if (name == "SELECT") state.select = true;
            else if (name == "UP") state.up = true;
            else if (name == "DOWN") state.down = true;
            else if (name == "LEFT") state.left = true;
            else if (name == "RIGHT") state.right = true;
            else return false;
        }
        return true;
    }

    void add_frames(JoypadState buttons, int frames) {
        if (frames > 0) {
            steps.push_back({Op::Frames, buttons, frames, ""});
        }
    }

    bool parse_line(std::string line) {
        auto hash = line.find('#');
        if (hash != std::string::npos) {
            line = line.substr(0, hash);
        }
        std::stringstream ss(line);
        std::string cmd;
        if (!(ss >> cmd)) {
            return true; // blank
        }
        std::string rest;
        std::getline(ss >> std::ws, rest);
        while (!rest.empty() && isspace(rest.back())) rest.pop_back();
        std::stringstream args(rest);

        if (cmd == "wait") {
            int n;
            if (!(args >> n)) return false;
            add_frames({}, n);
        } else if (cmd == "press") {
            std::string spec;
            int count = 1;
            if (!(args >> spec)) return false;
            args >> count;
            JoypadState buttons = {};
            if (!parse_buttons(spec, buttons)) return false;
            for (int i = 0; i < count; i++) {
                add_frames(buttons, TAP_HOLD);
                add_frames({}, TAP_RELEASE);
            }
        } else if (cmd == "hold") {
            std::string spec;
            int n;
            if (!(args >> spec >> n)) return false;
            JoypadState buttons = {};
            if (!parse_buttons(spec, buttons)) return false;
            add_frames(buttons, n);
        } else if (cmd == "screenshot") {
            if (rest.empty()) return false;
            steps.push_back({Op::Screenshot, {}, 0, rest});
        } else if (cmd == "sav") {
            if (rest.empty()) return false;
            steps.push_back({Op::Sav, {}, 0, rest});
        } else if (cmd == "dump") {
            std::string region, path;
            if (!(args >> region >> path)) return false;
            steps.push_back({Op::Dump, {}, 0, path, region});
        } else if (cmd == "echo") {
            steps.push_back({Op::Echo, {}, 0, rest});
        } else if (cmd == "quit") {
            steps.push_back({Op::Quit, {}, 0, ""});
        } else {
            return false;
        }
        return true;
    }
};

#!/data/data/com.termux/files/usr/bin/bash
#
#  __  __
# |  \/  | __ _ _   _  ___  _ __   __ _ _  ____ _
# | |\/| |/ _` | | | |/ _ \| '_ \ / _` | |/ / _` |
# | |  | | (_| | |_| | (_) | | | | (_| |   < (_| |
# |_|  |_|\__,_|\__, |\___/|_| |_|\__,_|_|\_\__,_|
#               |___/                     真夜中
#
# First-run provisioning for Mayonaka.
#
# Non-interactive, idempotent, safe to re-run: every step checks before it writes, and nothing
# you have edited by hand is clobbered. Re-run it any time with `bash ~/setup.sh`, or from
# Settings -> Mayonaka -> Re-run provisioning.
#
# Environment knobs:
#   MAYONAKA_FORCE=1     overwrite config files this script owns, even if they already exist
#   MAYONAKA_NO_PKG=1    skip the package installation step

set -u

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
HOME="${HOME:-/data/data/com.termux/files/home}"

FORCE="${MAYONAKA_FORCE:-0}"
NO_PKG="${MAYONAKA_NO_PKG:-0}"

# ------------------------------------------------------------------------------------------------
# Output helpers
# ------------------------------------------------------------------------------------------------

V=$'\033[38;2;139;92;246m'   # #8B5CF6
DIM=$'\033[38;2;154;149;181m'
RED=$'\033[38;2;224;85;97m'
GRN=$'\033[38;2;94;194;126m'
RST=$'\033[0m'

step()  { printf '%s::%s %s\n' "$V" "$RST" "$*"; }
info()  { printf '   %s%s%s\n' "$DIM" "$*" "$RST"; }
ok()    { printf '   %s✓%s %s\n' "$GRN" "$RST" "$*"; }
warn()  { printf '   %s!%s %s\n' "$RED" "$RST" "$*"; }

# Write $2 to the file $1 unless it already exists (or MAYONAKA_FORCE=1).
# Content comes from stdin.
write_if_absent() {
    local path="$1"
    mkdir -p "$(dirname "$path")"
    if [ -e "$path" ] && [ "$FORCE" != "1" ]; then
        # Drain stdin so the caller's heredoc does not end up on the terminal.
        cat > /dev/null
        info "kept your $path"
        return 0
    fi
    cat > "$path"
    ok "wrote $path"
}

banner() {
    printf '%s\n' "$V"
    cat <<'ART'
                         ／l、
                       （ﾟ､ ｡ ７        M A Y O N A K A
                         l  ~ヽ           真 夜 中
                         じしf_,)ノ
ART
    printf '%s\n' "$RST"
}

# ------------------------------------------------------------------------------------------------
# 1. Packages
# ------------------------------------------------------------------------------------------------

PACKAGES="fish starship fastfetch git openssh tmux fzf ripgrep bat eza zoxide micro curl wget jq python nodejs-lts termux-api termux-tools openssl"

install_packages() {
    step "Installing packages"
    if [ "$NO_PKG" = "1" ]; then
        info "MAYONAKA_NO_PKG=1, skipping"
        return 0
    fi
    if ! command -v pkg > /dev/null 2>&1; then
        warn "pkg not found -- is the bootstrap installed?"
        return 1
    fi

    info "updating package lists"
    pkg update -y > /dev/null 2>&1 || warn "pkg update failed, continuing with the cached lists"

    # One pkg call so apt resolves everything together; fall back to installing one at a time so a
    # single renamed or unavailable package cannot sink the whole run.
    # shellcheck disable=SC2086
    if pkg install -y $PACKAGES; then
        ok "packages installed"
    else
        warn "bulk install failed, retrying package by package"
        for p in $PACKAGES; do
            pkg install -y "$p" > /dev/null 2>&1 && ok "$p" || warn "could not install $p"
        done
    fi
}

# ------------------------------------------------------------------------------------------------
# 2. fish
# ------------------------------------------------------------------------------------------------

setup_fish() {
    step "Configuring fish"

    write_if_absent "$HOME/.config/fish/config.fish" <<'FISH'
# Mayonaka fish config.

if status is-interactive
    set -g fish_greeting

    # ---- prompt ------------------------------------------------------------------------------
    if type -q starship
        starship init fish | source
    end

    # ---- smarter cd --------------------------------------------------------------------------
    if type -q zoxide
        zoxide init fish | source
    end

    # ---- fuzzy finder ------------------------------------------------------------------------
    if type -q fzf
        # fzf >= 0.48 ships its own shell integration; older builds drop a file in share/.
        if fzf --fish >/dev/null 2>&1
            fzf --fish | source
        else if test -f $PREFIX/share/fzf/key-bindings.fish
            source $PREFIX/share/fzf/key-bindings.fish
        end
        set -gx FZF_DEFAULT_OPTS "--height 40% --layout=reverse --border=rounded \
            --color=bg+:#1a1a24,bg:#0a0a0f,spinner:#8B5CF6,hl:#8B5CF6 \
            --color=fg:#e4e2f0,header:#8B5CF6,info:#a78bfa,pointer:#8B5CF6 \
            --color=marker:#8B5CF6,fg+:#e4e2f0,prompt:#8B5CF6,hl+:#a78bfa \
            --color=border:#2a2a3a"
        if type -q rg
            set -gx FZF_DEFAULT_COMMAND 'rg --files --hidden --glob "!.git"'
        end
    end

    # ---- aliases -----------------------------------------------------------------------------
    if type -q eza
        alias ls  'eza --group-directories-first --icons'
        alias ll  'eza -l --group-directories-first --icons --git'
        alias la  'eza -la --group-directories-first --icons --git'
        alias lt  'eza --tree --level=2 --icons'
    else
        alias ll 'ls -l'
        alias la 'ls -la'
    end

    if type -q bat
        alias cat 'bat --paging=never --style=plain'
        set -gx BAT_THEME 'base16'
    end

    type -q micro; and set -gx EDITOR micro; or set -gx EDITOR vi
    set -gx VISUAL $EDITOR

    alias g   git
    alias gs  'git status --short --branch'
    alias ga  'git add'
    alias gc  'git commit'
    alias gp  'git push'
    alias gl  'git log --oneline --graph --decorate -20'
    alias gd  'git diff'

    alias ..   'cd ..'
    alias ...  'cd ../..'
    alias .... 'cd ../../..'

    alias reload 'source $HOME/.config/fish/config.fish'
    alias setup  'bash $HOME/setup.sh'

    # ---- fastfetch ---------------------------------------------------------------------------
    if type -q fastfetch
        alias nyafetch 'fastfetch --config $HOME/.config/fastfetch/config.jsonc'
        # The greeting. Comment this line out if you want a silent shell.
        nyafetch
    end
end
FISH

    write_if_absent "$HOME/.config/fish/conf.d/mayonaka_colors.fish" <<'FISH'
# Mayonaka fish syntax-highlighting colours.
set -g fish_color_normal        e4e2f0
set -g fish_color_command       8b5cf6 --bold
set -g fish_color_keyword       c084fc
set -g fish_color_quote         5ec27e
set -g fish_color_redirection   a78bfa
set -g fish_color_end           c084fc
set -g fish_color_error         e05561
set -g fish_color_param         c9c5dd
set -g fish_color_comment       4a4458
set -g fish_color_selection     --background=2a2a3a
set -g fish_color_search_match  --background=2a2a3a
set -g fish_color_operator      a78bfa
set -g fish_color_escape        5eead4
set -g fish_color_autosuggestion 4a4458
set -g fish_color_cwd           8b5cf6
set -g fish_color_user          a78bfa
set -g fish_color_host          8b5cf6

set -g fish_pager_color_progress    4a4458
set -g fish_pager_color_prefix      8b5cf6 --bold
set -g fish_pager_color_completion  e4e2f0
set -g fish_pager_color_description 4a4458
FISH

    # Make fish the login shell.
    local fish_path="$PREFIX/bin/fish"
    if [ ! -x "$fish_path" ]; then
        warn "fish is not installed, leaving the login shell alone"
        return 0
    fi

    if [ "$(basename "$(readlink -f "$PREFIX/bin/login-shell" 2>/dev/null || true)")" = "fish" ]; then
        info "fish is already the login shell"
    elif command -v chsh > /dev/null 2>&1; then
        if chsh -s fish > /dev/null 2>&1; then
            ok "fish set as the login shell"
        else
            warn "chsh failed; run 'chsh -s fish' yourself"
        fi
    else
        warn "chsh not found (install termux-tools); run 'chsh -s fish' yourself"
    fi
}

# ------------------------------------------------------------------------------------------------
# 3. starship
# ------------------------------------------------------------------------------------------------

setup_starship() {
    step "Configuring starship"

    write_if_absent "$HOME/.config/starship.toml" <<'TOML'
# Mayonaka starship prompt: a violet pill, powerline segments, Nerd Font glyphs.
#
#   #8B5CF6  accent   -- the user segment
#   #2a2a3a  muted    -- the directory segment
#   #1a1a24  surface  -- git and language segments

"$schema" = 'https://starship.rs/config-schema.json'

add_newline = true

format = """
[](#8B5CF6)\
$os\
$username\
[](fg:#8B5CF6 bg:#2a2a3a)\
$directory\
[](fg:#2a2a3a bg:#1a1a24)\
$git_branch\
$git_status\
$nodejs\
$python\
$rust\
$golang\
$cmd_duration\
[](fg:#1a1a24)\
$line_break\
$character"""

[os]
disabled = false
style = "bg:#8B5CF6 fg:#0a0a0f"
format = '[ $symbol ]($style)'

[os.symbols]
Android = "󰀲"
Linux = ""

[username]
show_always = true
style_user = "bg:#8B5CF6 fg:#0a0a0f bold"
style_root = "bg:#8B5CF6 fg:#0a0a0f bold"
format = '[$user ]($style)'

[directory]
style = "bg:#2a2a3a fg:#e4e2f0"
format = "[  $path ]($style)"
truncation_length = 3
truncation_symbol = "…/"
read_only = " 󰌾"

[directory.substitutions]
"/data/data/com.termux/files/home" = "~"
"~/Documents" = "󰈙 "
"~/Downloads" = " "
"~/storage" = "󰋊 "

[git_branch]
symbol = ""
style = "bg:#1a1a24 fg:#a78bfa"
format = '[ $symbol $branch ]($style)'

[git_status]
style = "bg:#1a1a24 fg:#e05561"
format = '[$all_status$ahead_behind ]($style)'

[nodejs]
symbol = ""
style = "bg:#1a1a24 fg:#5ec27e"
format = '[ $symbol $version ]($style)'

[python]
symbol = ""
style = "bg:#1a1a24 fg:#e3b341"
format = '[ $symbol $version ]($style)'

[rust]
symbol = ""
style = "bg:#1a1a24 fg:#e05561"
format = '[ $symbol $version ]($style)'

[golang]
symbol = ""
style = "bg:#1a1a24 fg:#5eead4"
format = '[ $symbol $version ]($style)'

[cmd_duration]
min_time = 2000
style = "bg:#1a1a24 fg:#9a95b5"
format = '[ 󱎫 $duration ]($style)'

[character]
success_symbol = "[󰄛 ❯](bold fg:#8B5CF6)"
error_symbol = "[󰄛 ❯](bold fg:#e05561)"
vimcmd_symbol = "[󰄛 ❮](bold fg:#a78bfa)"
TOML
}

# ------------------------------------------------------------------------------------------------
# 4. tmux
# ------------------------------------------------------------------------------------------------

setup_tmux() {
    step "Configuring tmux"

    write_if_absent "$HOME/.tmux.conf" <<'TMUX'
# Mayonaka tmux -- same midnight palette as the terminal.

set -g default-terminal "tmux-256color"
set -ga terminal-overrides ",xterm-256color:Tc,screen-256color:Tc"

set -g mouse on
set -g base-index 1
setw -g pane-base-index 1
set -g renumber-windows on
set -g history-limit 20000
set -sg escape-time 10
set -g focus-events on

# Ctrl-a is easier to reach than Ctrl-b on a phone keyboard.
unbind C-b
set -g prefix C-a
bind C-a send-prefix

bind | split-window -h -c "#{pane_current_path}"
bind - split-window -v -c "#{pane_current_path}"
bind r source-file ~/.tmux.conf \; display "reloaded"

bind -n M-Left  select-pane -L
bind -n M-Right select-pane -R
bind -n M-Up    select-pane -U
bind -n M-Down  select-pane -D

# ---- palette -------------------------------------------------------------------------------
# bg #0a0a0f | surface #1a1a24 | muted #2a2a3a | fg #e4e2f0 | dim #9a95b5 | accent #8b5cf6
# Colours are written out literally rather than via %hidden variables so this file works on
# every tmux that ships in Termux.

set -g status-style "bg=#0a0a0f,fg=#9a95b5"
set -g status-position bottom
set -g status-interval 5
set -g status-left-length 40
set -g status-right-length 80

set -g status-left "#[fg=#0a0a0f,bg=#8b5cf6,bold] 󰄛 #S #[fg=#8b5cf6,bg=#0a0a0f,nobold]"
set -g status-right "#[fg=#2a2a3a,bg=#0a0a0f]#[fg=#e4e2f0,bg=#2a2a3a] %H:%M #[fg=#8b5cf6,bg=#2a2a3a]#[fg=#0a0a0f,bg=#8b5cf6,bold] 󰥔 %d %b "

setw -g window-status-separator ""
setw -g window-status-format "#[fg=#9a95b5,bg=#0a0a0f]  #I #W  "
setw -g window-status-current-format "#[fg=#0a0a0f,bg=#1a1a24]#[fg=#8b5cf6,bg=#1a1a24,bold]  #I #W #[fg=#1a1a24,bg=#0a0a0f,nobold]"

set -g pane-border-style "fg=#2a2a3a"
set -g pane-active-border-style "fg=#8b5cf6"

set -g message-style "bg=#1a1a24,fg=#e4e2f0"
set -g message-command-style "bg=#1a1a24,fg=#e4e2f0"

setw -g mode-style "bg=#2a2a3a,fg=#e4e2f0"

set -g display-panes-colour "#2a2a3a"
set -g display-panes-active-colour "#8b5cf6"
TMUX
}

# ------------------------------------------------------------------------------------------------
# 5. fastfetch + the neko
# ------------------------------------------------------------------------------------------------

setup_fastfetch() {
    step "Configuring fastfetch"

    local dir="$HOME/.config/fastfetch"
    mkdir -p "$dir"

    # The art. Written verbatim; the colourised copy is generated from it, so editing neko.txt and
    # re-running this script restyles the greeting.
    write_if_absent "$dir/neko.txt" <<'NEKO'
⣿⠛⠛⠛⠛⠻⡆⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀
⠛⢛⣿⠋⢀⡾⠃⠀⠀⠀⠀⢀⣤⣤⠤⠤⣤⣤⣀⣀⣀⣠⠶⡶⣤⣀⣠⠾⡷⣦⣀⣤⣤⡤⠤⠦⢤⣤⣄⡀⠀⢠⡶⢶⡄⠀⠀
⢠⡟⠁⣴⣿⢤⡄⣴⢶⠶⡆⠈⢷⡀⠀⠀⠀⠀⢀⣭⣫⠵⠥⠽⣄⣝⠵⢍⣘⣄⠳⣤⣀⠀⠀⢀⡤⠊⣽⠁⠀⠸⣇⠀⢿⠀⠀
⠸⢷⣴⣤⡤⠾⠇⣽⠋⠼⣷⠀⠈⢷⡄⢀⣤⡶⠋⠀⣀⡄⠤⠀⡲⡆⠀⠀⠈⠙⡄⠘⢮⢳⡴⠯⣀⢠⡏⠀⠀⠀⢻⠀⢸⠇⠀
⠀⠀⠀⠀⠀⠀⠀⠙⠛⠋⠉⢀⣴⠟⠉⢯⡞⡠⢲⠉⣼⠀⠀⡰⠁⡇⢀⢷⠀⣄⢵⠀⠈⡟⢄⠀⠀⠙⢷⣤⣤⣤⡿⢢⡿⠀⠀
⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣠⠟⠑⠊⠁⡼⣌⢠⢿⢸⢸⡀⢰⠁⡸⡇⡸⣸⢰⢈⠘⡄⠀⢸⠀⢣⡀⠀⠈⢮⢢⣏⣤⡾⠃⠀⠀
⠀⠀⠀⠀⠀⠀⠀⠀⠀⢰⣯⣴⠞⡠⣼⠁⡘⣾⠏⣿⢇⣳⣸⣞⣀⢱⣧⣋⣞⡜⢳⡇⠀⢸⠀⢆⢧⠀⠰⣄⢏⢧⣾⠁⠀⠀⠀
⠀⠀⠀⠀⠀⠀⠀⠀⠀⠈⢹⡏⢰⠁⡻⠀⡟⡏⠉⠀⣀⠀⠀⠀⠀⣀⠁⠀⠉⠛⢽⠇⠀⣼⡆⠈⡆⠃⠀⡏⠻⣾⣽⣇⡀⠀⠀
⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢸⠁⡇⠀⡇⡄⣿⠷⠿⠿⠛⠀⠀⠀⠀⠛⠻⠿⠿⠿⡜⢀⡴⡟⢸⣸⡼⠀⠀⡇⠀⡞⡆⢻⠙⢦⠀
⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢸⡶⢀⣼⣿⣬⣽⠧⠬⠇⠀⠀⠀⠀⠀⠀⢞⣯⣭⢺⣔⣪⣾⣤⠺⡇⢳⠀⢠⣧⡾⠛⠛⠻⠶⠞⠁
⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠘⠷⢿⠟⠉⡀⠈⢦⡀⠀⠀⣠⠖⠒⠒⢤⡀⠀⢀⡼⠿⢇⡣⢬⣶⠷⢿⣤⡾⠁⠀⠀⠀⠀⠀⠀⠀
⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠘⠷⠾⠷⠖⠛⠛⠲⠶⠿⠤⣤⠤⠤⢷⣶⠋⠀⠀⠀⣱⠞⠁⠀⠈⠉⠀⠀⠀⠀⠀⠀⠀⠀⠀
⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠉⠛⠓⠒⠚⠋⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀
NEKO

    # Colourise: every line wrapped in a truecolor violet (139,92,246) escape. Regenerated on every
    # run so it always tracks neko.txt.
    awk '{ printf "\033[38;2;139;92;246m%s\033[0m\n", $0 }' "$dir/neko.txt" > "$dir/neko-violet.txt"
    ok "generated $dir/neko-violet.txt"

    write_if_absent "$dir/config.jsonc" <<JSONC
// Mayonaka fastfetch config. Aliased to \`nyafetch\` and run on shell start.
{
  "\$schema": "https://github.com/fastfetch-cli/fastfetch/raw/dev/doc/json_schema.json",
  "logo": {
    "type": "file-raw",
    "source": "$dir/neko-violet.txt",
    "padding": { "top": 1, "left": 1, "right": 3 }
  },
  "display": {
    "separator": "  ",
    "color": { "keys": "38;2;139;92;246", "title": "38;2;167;139;250" },
    "key": { "width": 9 }
  },
  "modules": [
    "break",
    { "type": "title", "format": "{user-name}{at-symbol}{host-name}" },
    { "type": "custom", "format": "[38;2;42;42;58m────────────────────────────[0m" },
    { "type": "os",        "key": "󰀲 os" },
    { "type": "kernel",    "key": "󰌢 kernel" },
    { "type": "uptime",    "key": "󰅐 uptime" },
    { "type": "packages",  "key": "󰏖 pkgs" },
    { "type": "shell",     "key": " shell" },
    { "type": "terminal",  "key": " term" },
    { "type": "cpu",       "key": "󰻠 cpu" },
    { "type": "memory",    "key": "󰍛 mem" },
    { "type": "disk",      "key": "󰋊 disk", "folders": "/data/data/com.termux/files" },
    { "type": "battery",   "key": "󰁹 batt" },
    { "type": "localip",   "key": "󰩟 ip" },
    { "type": "custom", "format": "[38;2;42;42;58m────────────────────────────[0m" },
    "colors",
    "break"
  ]
}
JSONC
}

# ------------------------------------------------------------------------------------------------
# 6. Widget shortcuts
# ------------------------------------------------------------------------------------------------

setup_shortcuts() {
    step "Scaffolding ~/.shortcuts"

    mkdir -p "$HOME/.shortcuts/tasks" "$HOME/.shortcuts/icons"

    write_if_absent "$HOME/.shortcuts/tmux-main" <<'SH'
#!/data/data/com.termux/files/usr/bin/bash
# Attach to the "main" tmux session, creating it if it is not running.
exec tmux new-session -A -s main
SH

    write_if_absent "$HOME/.shortcuts/update-packages" <<'SH'
#!/data/data/com.termux/files/usr/bin/bash
# Upgrade everything, then wait so the output stays readable.
set -e
pkg update -y
pkg upgrade -y
echo
echo "done -- press enter to close"
read -r _
SH

    write_if_absent "$HOME/.shortcuts/ssh-victus" <<'SH'
#!/data/data/com.termux/files/usr/bin/bash
# Jump to the laptop over Tailscale.
exec ssh victus
SH

    write_if_absent "$HOME/.shortcuts/nyafetch" <<'SH'
#!/data/data/com.termux/files/usr/bin/bash
fastfetch --config "$HOME/.config/fastfetch/config.jsonc"
echo
echo "press enter to close"
read -r _
SH

    # Background tasks: run without opening a terminal session.
    write_if_absent "$HOME/.shortcuts/tasks/battery" <<'SH'
#!/data/data/com.termux/files/usr/bin/bash
# Toast the battery state. Runs in the background, no session window.
if command -v termux-battery-status > /dev/null 2>&1; then
    termux-battery-status | jq -r '"\(.percentage)% \(.status) \(.temperature|round)C"' \
        | xargs -r termux-toast -g top -b '#1a1a24' -c '#8B5CF6'
else
    termux-toast -g top -b '#1a1a24' -c '#e05561' "termux-api is not installed"
fi
SH

    chmod 700 "$HOME"/.shortcuts/* 2>/dev/null || true
    chmod 700 "$HOME"/.shortcuts/tasks/* 2>/dev/null || true
    chmod 700 "$HOME/.shortcuts" "$HOME/.shortcuts/tasks"
    ok "shortcuts ready (long-press the home screen -> widgets -> Mayonaka)"
}

# ------------------------------------------------------------------------------------------------
# 7. ssh
# ------------------------------------------------------------------------------------------------

setup_ssh() {
    step "Configuring ssh"

    mkdir -p "$HOME/.ssh"
    chmod 700 "$HOME/.ssh"
    touch "$HOME/.ssh/config"
    chmod 600 "$HOME/.ssh/config"

    if grep -qE '^[[:space:]]*Host[[:space:]]+victus([[:space:]]|$)' "$HOME/.ssh/config"; then
        info "host 'victus' is already configured"
        return 0
    fi

    cat >> "$HOME/.ssh/config" <<'SSH'

# The laptop, over Tailscale.
Host victus
    HostName 100.83.14.4
    ForwardAgent no
    ServerAliveInterval 30
    ServerAliveCountMax 4
SSH
    ok "added host 'victus' -> 100.83.14.4"
}

# ------------------------------------------------------------------------------------------------
# 8. termux-api component patch
# ------------------------------------------------------------------------------------------------
#
# $PREFIX/libexec/termux-api is a C helper that broadcasts to a component string compiled into the
# binary: "com.termux.api/.TermuxApiReceiver" (see termux-api-package/termux-api.c, child_argv[5]).
# Mayonaka merges the receiver into the com.termux app itself, so that component no longer exists
# and every termux-* command would hang. The merged receiver is declared as the class
# com.termux.api.TermuxApiReceiver, which makes its component "com.termux/.api.TermuxApiReceiver" --
# exactly the same 33 bytes as the original, so the installed binary can be patched in place with a
# same-length replacement.
#
# The patcher is installed as $PREFIX/bin/mayonaka-patch-api and re-run from an apt hook after
# every package operation, because `pkg upgrade termux-api` reinstalls the pristine binary.

install_api_patcher() {
    step "Installing the termux-api component patcher"

    write_if_absent "$PREFIX/bin/mayonaka-patch-api" <<'PATCHER'
#!/data/data/com.termux/files/usr/bin/bash
#
# Rewrite the component string baked into $PREFIX/libexec/termux-api so that it targets the
# receiver merged into the Mayonaka app. Both strings are 33 bytes, so this is an in-place,
# same-length binary edit -- no relocation, no size change.
#
#   com.termux.api/.TermuxApiReceiver   (stock, 33 bytes)
#   com.termux/.api.TermuxApiReceiver   (Mayonaka, 33 bytes)
#
# Idempotent and safe to run when termux-api is not installed.

set -u
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
BIN="$PREFIX/libexec/termux-api"

[ -f "$BIN" ] || { [ "${1:-}" = "--quiet" ] || echo "mayonaka-patch-api: $BIN not present, nothing to do"; exit 0; }

if ! command -v python3 > /dev/null 2>&1; then
    echo "mayonaka-patch-api: python3 is required for the binary-safe replace (pkg install python)" >&2
    exit 1
fi

python3 - "$BIN" "${1:-}" <<'PY'
import os
import sys

OLD = b"com.termux.api/.TermuxApiReceiver"
NEW = b"com.termux/.api.TermuxApiReceiver"
assert len(OLD) == len(NEW) == 33, "replacement must be the same length as the original"

path = sys.argv[1]
quiet = len(sys.argv) > 2 and sys.argv[2] == "--quiet"

with open(path, "rb") as f:
    data = f.read()

old_count = data.count(OLD)
new_count = data.count(NEW)

if old_count == 0:
    if new_count:
        if not quiet:
            print("mayonaka-patch-api: already patched (%d occurrence(s))" % new_count)
        sys.exit(0)
    print("mayonaka-patch-api: neither component string found in %s -- "
          "the termux-api package layout changed, patch by hand" % path, file=sys.stderr)
    sys.exit(3)

patched = data.replace(OLD, NEW)
assert len(patched) == len(data), "length changed, refusing to write"

# Write via a temporary file in the same directory so a failure cannot leave a half-written binary.
tmp = path + ".mayonaka.tmp"
mode = os.stat(path).st_mode
with open(tmp, "wb") as f:
    f.write(patched)
os.chmod(tmp, mode)
os.replace(tmp, path)

# Verify the patch actually took.
with open(path, "rb") as f:
    check = f.read()
if check.count(NEW) != old_count + new_count or OLD in check:
    print("mayonaka-patch-api: verification FAILED for %s" % path, file=sys.stderr)
    sys.exit(4)

if not quiet:
    print("mayonaka-patch-api: patched %d occurrence(s) in %s" % (old_count, path))
PY
PATCHER
    chmod 700 "$PREFIX/bin/mayonaka-patch-api"

    write_if_absent "$PREFIX/etc/apt/apt.conf.d/99-mayonaka-api-patch" <<'APT'
// Mayonaka: re-apply the termux-api component patch after every package operation, because
// installing or upgrading the termux-api package restores the pristine binary.
DPkg::Post-Invoke { "if [ -x /data/data/com.termux/files/usr/bin/mayonaka-patch-api ]; then /data/data/com.termux/files/usr/bin/mayonaka-patch-api --quiet || true; fi"; };
APT

    step "Patching the termux-api binary"
    if "$PREFIX/bin/mayonaka-patch-api"; then
        ok "termux-api points at com.termux/.api.TermuxApiReceiver"
    else
        warn "the termux-api patch did not apply -- termux-* commands may not work"
    fi
}

# ------------------------------------------------------------------------------------------------
# 9. Odds and ends
# ------------------------------------------------------------------------------------------------

setup_misc() {
    step "Miscellaneous"

    # bash users get the same greeting and aliases, in case fish is not the login shell.
    if ! grep -q 'Mayonaka' "$HOME/.bashrc" 2> /dev/null; then
        cat >> "$HOME/.bashrc" <<'BASH'

# ---- Mayonaka ----------------------------------------------------------------------------
export EDITOR="${EDITOR:-micro}"
alias nyafetch='fastfetch --config "$HOME/.config/fastfetch/config.jsonc"'
alias ll='eza -l --group-directories-first --icons --git 2>/dev/null || ls -l'
alias setup='bash "$HOME/setup.sh"'
command -v starship > /dev/null 2>&1 && eval "$(starship init bash)"
command -v zoxide   > /dev/null 2>&1 && eval "$(zoxide init bash)"
command -v fastfetch > /dev/null 2>&1 && nyafetch
BASH
        ok "appended the Mayonaka block to ~/.bashrc"
    else
        info "~/.bashrc already has the Mayonaka block"
    fi

    # git is friendlier with a pager that understands the colours.
    if command -v git > /dev/null 2>&1; then
        git config --global --get core.pager > /dev/null 2>&1 || git config --global core.pager "less -FRX"
        git config --global --get init.defaultBranch > /dev/null 2>&1 || git config --global init.defaultBranch main
    fi

    mkdir -p "$HOME/storage" 2> /dev/null || true
}

# ------------------------------------------------------------------------------------------------

main() {
    banner
    install_packages
    setup_fish
    setup_starship
    setup_tmux
    setup_fastfetch
    setup_shortcuts
    setup_ssh
    install_api_patcher
    setup_misc

    printf '\n%s::%s %sprovisioning complete.%s\n' "$V" "$RST" "$V" "$RST"
    info "restart the session (or run 'exec fish') to pick up the new shell"
}

main "$@"

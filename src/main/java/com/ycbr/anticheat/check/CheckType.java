package com.ycbr.anticheat.check;

public enum CheckType {

    KILLAURA("KillAura", "killaura"),
    SCAFFOLD("Scaffold", "scaffold"),
    SPEED("Speed", "speed"),
    VELOCITY("Velocity", "velocity"),
    FLY("Fly", "fly"),
    CRITICALS("Criticals", "criticals"),
    TIMER("Timer", "timer"),
    NOFALL("NoFall", "nofall"),
    WRONGTURN("WrongTurn", "wrongturn"),
    NOSWING("NoSwing", "noswing"),
    BLINK("Blink", "blink"),
    SPRINT("Sprint", "sprint"),
    BADPACKET("BadPacket", "badpacket"),
    NOSLOW("NoSlow", "noslow"),
    AUTOTOOL("AutoTool", "autotool"),
    FASTTHROW("FastThrow", "fastthrow"),
    INSTANTBOW("InstantBow", "instantbow"),
    FASTCLICK("FastClick", "fastclick"),
    REACH("Reach", "reach"),
    SIMULATION("Simulation", "simulation"),
    AIMSTAT("AimStat", "aimstat");

    private final String display;
    private final String configPath;

    CheckType(String display, String configPath) {
        this.display = display;
        this.configPath = configPath;
    }

    public String getDisplay() {
        return display;
    }

    public String getConfigPath() {
        return configPath;
    }
}
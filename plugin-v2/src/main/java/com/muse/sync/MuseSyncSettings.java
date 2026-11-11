package com.muse.sync;

import com.intellij.ide.util.PropertiesComponent;

/** Simple stored settings: relay URL, pairing code, auto-open. */
public class MuseSyncSettings {
    private static final MuseSyncSettings INSTANCE = new MuseSyncSettings();
    public static MuseSyncSettings getInstance() { return INSTANCE; }

    private PropertiesComponent props() { return PropertiesComponent.getInstance(); }

    public String getRelayUrl() { return props().getValue("muse.sync.relayUrl", ""); }
    public void setRelayUrl(String v) { props().setValue("muse.sync.relayUrl", v); }
    public String getPairingCode() { return props().getValue("muse.sync.pairingCode", ""); }
    public void setPairingCode(String v) { props().setValue("muse.sync.pairingCode", v); }
    public boolean isAutoOpen() { return props().getBoolean("muse.sync.autoOpen", true); }
    public void setAutoOpen(boolean v) { props().setValue("muse.sync.autoOpen", Boolean.toString(v)); }
}

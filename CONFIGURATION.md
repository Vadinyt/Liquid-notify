# LiquidNotify configuration (One UI 9)

This preview preserves Samsung's notification views, touch handling, expansion and dismissal. It changes their native radius and tint only. **It does not implement per-card backdrop blur or a settings app.**

Requires root shell or authorized ADB to change `Settings.Global`. Defaults work without any commands. Some changes may not show until Samsung calls `setTint` or `setRadius` again, such as when reopening the shade or receiving a notification.

## Commands (rooted Android / Termux)

Open a root shell with `su` first. Then:

```sh
settings put global liquidnotify_opacity 185
settings put global liquidnotify_palette 0
settings put global liquidnotify_radius_dp 36
```

Settings:

| Key | Values | Default |
| --- | --- | --- |
| `liquidnotify_enabled` | 0 off / 1 on | 1 |
| `liquidnotify_color` | 0 original Samsung tint / 1 custom | 1 |
| `liquidnotify_radius_enabled` | 0 original Samsung radius / 1 custom | 1 |
| `liquidnotify_radius_dp` | 18 to 48 (minimum radius; OEM larger values are preserved) | 36 |
| `liquidnotify_opacity` | 125 to 245 (higher means more opaque) | 185 |
| `liquidnotify_palette` | 0 icy blue, 1 graphite, 2 violet | 0 |

Try opacity 160 for stronger translucency; if text becomes hard to read, return to 185 or higher. These values apply only to the unlocked notification shade; keyguard is excluded.

## Recovery

If the visuals are unreadable, run `su -c 'settings put global liquidnotify_enabled 0'` in Termux, then reopen the notification shade. If SystemUI crashes, disable LiquidNotify in LSPosed and reboot.

## Design limitations

Samsung may override the alpha channel internally when it draws notification backgrounds. In that case opacity settings may not yield visible wallpaper translucency. Genuine backdrop blur requires verified One UI 9 native APIs and device testing. No foreground drawable overlay is used, avoiding the v0.4 rectangular border artifact.

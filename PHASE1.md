# Phase 1: Framework investigation

## Target
Samsung Galaxy S23+ SM-S916B/DS, One UI 9, Android 17 (API 37), firmware S916BXXUAGZIF, KernelSU and LSPosed 2.2.1 API 102.

## APK discovery
Previous local DEX class-table inspection of the supplied `SystemUI.apk` identified these candidate class names in `classes3.dex`:

- `ExpandableNotificationRow`
- `NotificationStackScrollLayout`
- `NotificationShelf`
- `NotificationPanelViewController`
- `SecNotificationPanelViewController`
- `SecNotificationsQSContainerController`

Presence does not prove active usage or safe hook methods. Verify full names, signatures, lifecycle and view hierarchy before installing hooks.

## Hook strategy
1. Inspect method signatures using jadx or dex tools.
2. Add diagnostic hooks only to verified methods and isolate errors.
3. Confirm logs and active views on target device.
4. Add one reversible visual surface modification, initially without blur.
5. Test recycling, grouping, expansion, replies, gestures and lock-screen privacy.

## Rendering constraints
Android `RenderEffect` is not automatically a backdrop blur. Avoid bitmap capture, drawing-loop allocations and touch interception. Prefer verified Samsung native backdrop implementation, or transparent gradients without blur.

## Build status
The scaffold refers to `app/libs/xposed-api.jar`, which is not bundled. The Gradle wrapper is also absent. No compilation or device validation has occurred.

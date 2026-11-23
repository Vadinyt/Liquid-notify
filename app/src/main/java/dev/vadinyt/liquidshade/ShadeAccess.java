package dev.vadinyt.liquidshade;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.UserManager;
import java.lang.reflect.Field;

final class ShadeAccess {
  static boolean unlocked(Context context) {
    UserManager users = context.getSystemService(UserManager.class);
    KeyguardManager lock = context.getSystemService(KeyguardManager.class);
    return users != null
        && lock != null
        && users.isUserForeground()
        && users.isUserUnlocked()
        && !lock.isKeyguardLocked();
  }

  // Samsung initializes the child background to locked. The authoritative flag belongs to its row.
  static boolean unlockedRow(Field lockFlag, Object row) throws IllegalAccessException {
    return row != null
        && lockFlag != null
        && lockFlag.getDeclaringClass().isInstance(row)
        && !lockFlag.getBoolean(row);
  }
}

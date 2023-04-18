// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
package io.flutter.plugins.localauth;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import io.flutter.plugin.common.MethodCall;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;

/**
 * Authenticates the user with biometrics and sends corresponding response back to Flutter.
 *
 * <p>One instance per call is generated to ensure readable separation of executable paths across
 * method calls.
 */
@SuppressWarnings("deprecation")
class AuthenticationHelper extends BiometricPrompt.AuthenticationCallback
    implements Application.ActivityLifecycleCallbacks, DefaultLifecycleObserver {
  /** The callback that handles the result of this authentication process. */
  interface AuthCompletionHandler {
    /** Called when authentication was successful. */
    void onSuccess();

    /**
     * Called when authentication failed due to user. For instance, when user cancels the auth or
     * quits the app.
     */
    void onFailure();

    /**
     * Called when authentication fails due to non-user related problems such as system errors,
     * phone not having a FP reader etc.
     *
     * @param code The error code to be returned to Flutter app.
     * @param error The description of the error.
     */
    void onError(String code, String error);
  }

  // This is null when not using v2 embedding;
  private final Lifecycle lifecycle;
  private final FragmentActivity activity;
  private final AuthCompletionHandler completionHandler;
  private final MethodCall call;
  private final BiometricPrompt.PromptInfo promptInfo;
  private final boolean isAuthSticky;
  private final UiThreadExecutor uiThreadExecutor;
  private boolean activityPaused = false;
  private BiometricPrompt biometricPrompt;

  @RequiresApi(api = Build.VERSION_CODES.M)
  AuthenticationHelper(
      Lifecycle lifecycle,
      FragmentActivity activity,
      MethodCall call,
      AuthCompletionHandler completionHandler,
      boolean allowCredentials) throws NoSuchAlgorithmException, InvalidAlgorithmParameterException, NoSuchProviderException {
    this.lifecycle = lifecycle;
    this.activity = activity;
    this.completionHandler = completionHandler;
    this.call = call;
    this.isAuthSticky = call.argument("stickyAuth");
    this.uiThreadExecutor = new UiThreadExecutor();

    generateSecretKey(new KeyGenParameterSpec.Builder(
            "BiometricAuth"

            ,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
            .setUserAuthenticationRequired(true)
            .build());


    BiometricPrompt.PromptInfo.Builder promptBuilder =
        new BiometricPrompt.PromptInfo.Builder()
            .setDescription((String) call.argument("localizedReason"))
            .setTitle((String) call.argument("signInTitle"))
            .setSubtitle((String) call.argument("biometricHint"))
            .setConfirmationRequired((Boolean) call.argument("sensitiveTransaction"))
            .setConfirmationRequired((Boolean) call.argument("sensitiveTransaction"));

    int allowedAuthenticators =
        BiometricManager.Authenticators.BIOMETRIC_STRONG;

    if (allowCredentials) {
      allowedAuthenticators |= BiometricManager.Authenticators.DEVICE_CREDENTIAL;
    } else {
      promptBuilder.setNegativeButtonText((String) call.argument("cancelButton"));
    }

    promptBuilder.setAllowedAuthenticators(allowedAuthenticators);
    this.promptInfo = promptBuilder.build();
  }

  private void generateSecretKey(KeyGenParameterSpec keyGenParameterSpec) throws NoSuchAlgorithmException, NoSuchProviderException, InvalidAlgorithmParameterException {
    KeyGenerator keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      keyGenerator.init(keyGenParameterSpec);
    }
    keyGenerator.generateKey();
  }

  private SecretKey getSecretKey() throws KeyStoreException, CertificateException, IOException, NoSuchAlgorithmException, UnrecoverableKeyException {
    KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");

    // Before the keystore can be accessed, it must be loaded.
    keyStore.load(null);
    return ((SecretKey)keyStore.getKey("BiometricAuth"

            , null));
  }

  private Cipher getCipher() throws NoSuchPaddingException, NoSuchAlgorithmException {
    return Cipher.getInstance(KeyProperties.KEY_ALGORITHM_AES + "/"
            + KeyProperties.BLOCK_MODE_CBC + "/"
            + KeyProperties.ENCRYPTION_PADDING_PKCS7);
  }


  /** Start the biometric listener. */
  void authenticate() throws NoSuchPaddingException, NoSuchAlgorithmException, UnrecoverableKeyException, CertificateException, KeyStoreException, IOException, InvalidKeyException {
    if (lifecycle != null) {
      lifecycle.addObserver(this);
    } else {
      activity.getApplication().registerActivityLifecycleCallbacks(this);
    }
    biometricPrompt = new BiometricPrompt(activity, uiThreadExecutor, this);
    Cipher cipher = getCipher();
    SecretKey secretKey = getSecretKey();
    cipher.init(Cipher.ENCRYPT_MODE, secretKey);

    biometricPrompt.authenticate(promptInfo,
            new BiometricPrompt.CryptoObject(cipher)
            );
  }

  /** Cancels the biometric authentication. */
  void stopAuthentication() {
    if (biometricPrompt != null) {
      biometricPrompt.cancelAuthentication();
      biometricPrompt = null;
    }
  }

  /** Stops the biometric listener. */
  private void stop() {
    if (lifecycle != null) {
      lifecycle.removeObserver(this);
      return;
    }
    activity.getApplication().unregisterActivityLifecycleCallbacks(this);
  }

  @SuppressLint("SwitchIntDef")
  @Override
  public void onAuthenticationError(int errorCode, CharSequence errString) {
    switch (errorCode) {
      case BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL:
        if (call.argument("useErrorDialogs")) {
          showGoToSettingsDialog(
              (String) call.argument("deviceCredentialsRequired"),
              (String) call.argument("deviceCredentialsSetupDescription"));
          return;
        }
        completionHandler.onError("NotAvailable", "Security credentials not available.");
        break;
      case BiometricPrompt.ERROR_NO_SPACE:
      case BiometricPrompt.ERROR_NO_BIOMETRICS:
        if (call.argument("useErrorDialogs")) {
          showGoToSettingsDialog(
              (String) call.argument("biometricRequired"),
              (String) call.argument("goToSettingDescription"));
          return;
        }
        completionHandler.onError("NotEnrolled", "No Biometrics enrolled on this device.");
        break;
      case BiometricPrompt.ERROR_HW_UNAVAILABLE:
      case BiometricPrompt.ERROR_HW_NOT_PRESENT:
        completionHandler.onError("NotAvailable", "Security credentials not available.");
        break;
      case BiometricPrompt.ERROR_LOCKOUT:
        completionHandler.onError(
            "LockedOut",
            "The operation was canceled because the API is locked out due to too many attempts. This occurs after 5 failed attempts, and lasts for 30 seconds.");
        break;
      case BiometricPrompt.ERROR_LOCKOUT_PERMANENT:
        completionHandler.onError(
            "PermanentlyLockedOut",
            "The operation was canceled because ERROR_LOCKOUT occurred too many times. Biometric authentication is disabled until the user unlocks with strong authentication (PIN/Pattern/Password)");
        break;
      case BiometricPrompt.ERROR_CANCELED:
        // If we are doing sticky auth and the activity has been paused,
        // ignore this error. We will start listening again when resumed.
        if (activityPaused && isAuthSticky) {
          return;
        } else {
          completionHandler.onFailure();
        }
        break;
      default:
        completionHandler.onFailure();
    }
    stop();
  }

  @Override
  public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
    try {
//      BiometricPrompt.CryptoObject cryptoObject= result.getCryptoObject();
//      if(cryptoObject==null){
//        completionHandler.onError("UNKNOWN-NULL", "crypto object null");
//        stop();
//        return;
//      }
//      Cipher cipher =  cryptoObject.getCipher();
//      if(cipher==null){
//        completionHandler.onError("UNKNOWN-NULL", "cipher object null");
//        stop();
//        return;
//      }
//      byte[] encryptedInfo = cipher.doFinal(
//              "SECRETBiometric"
//
//                      .getBytes(Charset.defaultCharset()));

      completionHandler.onSuccess();


//    } catch (BadPaddingException e) {
//      e.printStackTrace();
//    } catch (IllegalBlockSizeException e) {
//      e.printStackTrace();
//    }
    }
    catch (Exception e){
      String trace = e.getStackTrace().toString();
      String error = e.toString();
      completionHandler.onError("UNKNOWN-NULL", "null pointer exception "+error+trace);
    }
    stop();
  }

  @Override
  public void onAuthenticationFailed() {}

  /**
   * If the activity is paused, we keep track because biometric dialog simply returns "User
   * cancelled" when the activity is paused.
   */
  @Override
  public void onActivityPaused(Activity ignored) {
    if (isAuthSticky) {
      activityPaused = true;
    }
  }

  @Override
  public void onActivityResumed(Activity ignored) {
    if (isAuthSticky) {
      activityPaused = false;
      final BiometricPrompt prompt = new BiometricPrompt(activity, uiThreadExecutor, this);
      // When activity is resuming, we cannot show the prompt right away. We need to post it to the
      // UI queue.
      uiThreadExecutor.handler.post(
          new Runnable() {
            @Override
            public void run() {
              prompt.authenticate(promptInfo);
            }
          });
    }
  }

  @Override
  public void onPause(@NonNull LifecycleOwner owner) {
    onActivityPaused(null);
  }

  @Override
  public void onResume(@NonNull LifecycleOwner owner) {
    onActivityResumed(null);
  }

  // Suppress inflateParams lint because dialogs do not need to attach to a parent view.
  @SuppressLint("InflateParams")
  private void showGoToSettingsDialog(String title, String descriptionText) {
    View view = LayoutInflater.from(activity).inflate(R.layout.go_to_setting, null, false);
    TextView message = (TextView) view.findViewById(R.id.fingerprint_required);
    TextView description = (TextView) view.findViewById(R.id.go_to_setting_description);
    message.setText(title);
    description.setText(descriptionText);
    Context context = new ContextThemeWrapper(activity, R.style.AlertDialogCustom);
    OnClickListener goToSettingHandler =
        new OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            completionHandler.onFailure();
            stop();
            activity.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
          }
        };
    OnClickListener cancelHandler =
        new OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            completionHandler.onFailure();
            stop();
          }
        };
    new AlertDialog.Builder(context)
        .setView(view)
        .setPositiveButton((String) call.argument("goToSetting"), goToSettingHandler)
        .setNegativeButton((String) call.argument("cancelButton"), cancelHandler)
        .setCancelable(false)
        .show();
  }

  // Unused methods for activity lifecycle.

  @Override
  public void onActivityCreated(Activity activity, Bundle bundle) {}

  @Override
  public void onActivityStarted(Activity activity) {}

  @Override
  public void onActivityStopped(Activity activity) {}

  @Override
  public void onActivitySaveInstanceState(Activity activity, Bundle bundle) {}

  @Override
  public void onActivityDestroyed(Activity activity) {}

  @Override
  public void onDestroy(@NonNull LifecycleOwner owner) {}

  @Override
  public void onStop(@NonNull LifecycleOwner owner) {}

  @Override
  public void onStart(@NonNull LifecycleOwner owner) {}

  @Override
  public void onCreate(@NonNull LifecycleOwner owner) {}

  private static class UiThreadExecutor implements Executor {
    final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void execute(Runnable command) {
      handler.post(command);
    }
  }
}

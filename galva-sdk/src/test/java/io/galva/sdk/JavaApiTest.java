package io.galva.sdk;

import androidx.activity.ComponentActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.util.HashMap;
import java.util.Map;

/**
 * The public API as a Java app calls it: compiling this file is most of the test. The calls run
 * against the unconfigured singleton, where each one is a safe no-op.
 */
@RunWith(RobolectricTestRunner.class)
public class JavaApiTest {

    @Test
    public void javaAppsCanUseThePublicApi() {
        ComponentActivity activity = Robolectric.buildActivity(ComponentActivity.class).setup().get();
        Galva galva = Galva.getInstance();

        Configuration configuration = new Configuration(
                "pk_test", LogLevel.DEBUG, true, Environment.Development.INSTANCE);
        String version = Galva.getSdkVersion();

        galva.observeInAppMessages(activity, message -> galva.showMessage(activity, message));
        galva.checkForMessages();

        galva.identify("user_42");
        galva.identify("user_42", "jane@example.com");
        galva.updateProperties(
                new ProfileProperty.Email("jane@example.com"),
                new ProfileProperty.Custom("plan_tier", "pro"));

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("sku", "pro_yearly");
        attributes.put("price", 9.99);
        galva.track("Purchase", attributes);

        galva.setPushToken("fcm-token");
        galva.setPushToken("hms-token", PushProvider.HMS);
        galva.setLogLevel(LogLevel.DEBUG);
        galva.setLogger((level, tag, message, throwable) -> { });
        galva.logout();
    }
}

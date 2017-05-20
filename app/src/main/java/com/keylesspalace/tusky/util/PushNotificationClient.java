package com.keylesspalace.tusky.util;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.support.annotation.NonNull;
import android.text.Spanned;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.keylesspalace.tusky.R;
import com.keylesspalace.tusky.entity.Notification;
import com.keylesspalace.tusky.json.SpannedTypeAdapter;
import com.keylesspalace.tusky.json.StringWithEmoji;
import com.keylesspalace.tusky.json.StringWithEmojiTypeAdapter;
import com.keylesspalace.tusky.network.MastodonAPI;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import static android.content.Context.NOTIFICATION_SERVICE;

public class PushNotificationClient {
    private static final String TAG = "PushNotificationClient";
    private static final int NOTIFY_ID = 666;

    private static class QueuedAction {
        enum Type {
            SUBSCRIBE,
            UNSUBSCRIBE,
            DISCONNECT,
        }

        Type type;
        String topic;

        QueuedAction(Type type) {
            this.type = type;
        }

        QueuedAction(Type type, String topic) {
            this.type = type;
            this.topic = topic;
        }
    }

    private MqttAndroidClient mqttAndroidClient;
    private MastodonAPI mastodonApi;
    private ArrayDeque<QueuedAction> queuedActions;
    private ArrayList<String> subscribedTopics;

    public PushNotificationClient(final @NonNull Context applicationContext,
                                  @NonNull String serverUri) {
        queuedActions = new ArrayDeque<>();
        subscribedTopics = new ArrayList<>();

        // Create the MQTT client.
        String clientId = MqttClient.generateClientId();
        mqttAndroidClient = new MqttAndroidClient(applicationContext, serverUri, clientId);
        mqttAndroidClient.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                if (reconnect) {
                    flushQueuedActions();
                    for (String topic : subscribedTopics) {
                        subscribeToTopic(topic);
                    }
                }
            }

            @Override
            public void connectionLost(Throwable cause) {
                onConnectionLost();
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {
                onMessageReceived(applicationContext, new String(message.getPayload()));
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // This client is read-only, so this is unused.
            }
        });

        // Open the MQTT connection.
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(false);
        try {
            /*
            String password = context.getString(R.string.tusky_api_keystore_password);
            InputStream keystore = context.getResources().openRawResource(R.raw.keystore_tusky_api);
            try {
                options.setSocketFactory(mqttAndroidClient.getSSLSocketFactory(keystore, password));
            } finally {
                IOUtils.closeQuietly(keystore);
            }
            */
            mqttAndroidClient.connect(options).setActionCallback(new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    DisconnectedBufferOptions bufferOptions = new DisconnectedBufferOptions();
                    bufferOptions.setBufferEnabled(true);
                    bufferOptions.setBufferSize(100);
                    bufferOptions.setPersistBuffer(false);
                    bufferOptions.setDeleteOldestMessages(false);
                    mqttAndroidClient.setBufferOpts(bufferOptions);
                    onConnectionSuccess();
                    flushQueuedActions();
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Log.e(TAG, "An exception occurred while connecting. " + exception.getMessage());
                    onConnectionFailure();
                }
            });
        } catch (MqttException e) {
            Log.e(TAG, "An exception occurred while connecting. " + e.getMessage());
            onConnectionFailure();
        }
    }

    private void flushQueuedActions() {
        while (!queuedActions.isEmpty()) {
            QueuedAction action = queuedActions.pop();
            switch (action.type) {
                case SUBSCRIBE:   subscribeToTopic(action.topic);   break;
                case UNSUBSCRIBE: unsubscribeToTopic(action.topic); break;
                case DISCONNECT:  disconnect();                     break;
            }
        }
    }

    /** Disconnect from the MQTT broker. */
    public void disconnect() {
        if (!mqttAndroidClient.isConnected()) {
            queuedActions.add(new QueuedAction(QueuedAction.Type.DISCONNECT));
            return;
        }
        try {
            mqttAndroidClient.disconnect();
        } catch (MqttException ex) {
            Log.e(TAG, "An exception occurred while disconnecting.");
            onDisconnectFailed();
        }
    }

    private void onDisconnectFailed() {
        Log.v(TAG, "Failed while disconnecting from the broker.");
    }

    /** Subscribe to the push notification topic. */
    public void subscribeToTopic(final String topic) {
        if (!mqttAndroidClient.isConnected()) {
            queuedActions.add(new QueuedAction(QueuedAction.Type.SUBSCRIBE, topic));
            return;
        }
        try {
            mqttAndroidClient.subscribe(topic, 0, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    subscribedTopics.add(topic);
                    onConnectionSuccess();
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Log.e(TAG, "An exception occurred while subscribing." + exception.getMessage());
                    onConnectionFailure();
                }
            });
        } catch (MqttException e) {
            Log.e(TAG, "An exception occurred while subscribing." + e.getMessage());
            onConnectionFailure();
        }
    }

    /** Unsubscribe from the push notification topic. */
    public void unsubscribeToTopic(String topic) {
        if (!mqttAndroidClient.isConnected()) {
            queuedActions.add(new QueuedAction(QueuedAction.Type.UNSUBSCRIBE, topic));
            return;
        }
        try {
            mqttAndroidClient.unsubscribe(topic);
            subscribedTopics.remove(topic);
        } catch (MqttException e) {
            Log.e(TAG, "An exception occurred while unsubscribing." + e.getMessage());
            onConnectionFailure();
        }
    }

    private void onConnectionSuccess() {
        Log.v(TAG, "The connection succeeded.");
    }

    private void onConnectionFailure() {
        Log.v(TAG, "The connection failed.");
    }

    private void onConnectionLost() {
        Log.v(TAG, "The connection was lost.");
    }

    private void onMessageReceived(final Context context, String message) {
        String notificationId = message; // TODO: finalize the form the messages will be received

        Log.v(TAG, "Notification received: " + notificationId);

        createMastodonAPI(context);

        mastodonApi.notification(notificationId).enqueue(new Callback<Notification>() {
            @Override
            public void onResponse(Call<Notification> call, Response<Notification> response) {
                if (response.isSuccessful()) {
                    NotificationMaker.make(context, NOTIFY_ID, response.body());
                }
            }

            @Override
            public void onFailure(Call<Notification> call, Throwable t) {}
        });
    }

    private void createMastodonAPI(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(
                context.getString(R.string.preferences_file_key), Context.MODE_PRIVATE);
        final String domain = preferences.getString("domain", null);
        final String accessToken = preferences.getString("accessToken", null);

        OkHttpClient okHttpClient = OkHttpUtils.getCompatibleClientBuilder()
                .addInterceptor(new Interceptor() {
                    @Override
                    public okhttp3.Response intercept(Chain chain) throws IOException {
                        Request originalRequest = chain.request();

                        Request.Builder builder = originalRequest.newBuilder()
                                .header("Authorization", String.format("Bearer %s", accessToken));

                        Request newRequest = builder.build();

                        return chain.proceed(newRequest);
                    }
                })
                .build();

        Gson gson = new GsonBuilder()
                .registerTypeAdapter(Spanned.class, new SpannedTypeAdapter())
                .registerTypeAdapter(StringWithEmoji.class, new StringWithEmojiTypeAdapter())
                .create();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl("https://" + domain)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build();

        mastodonApi = retrofit.create(MastodonAPI.class);
    }

    public void clearNotifications(Context context) {
        ((NotificationManager) (context.getSystemService(NOTIFICATION_SERVICE))).cancel(NOTIFY_ID);
    }

    public String getDeviceToken() {
        return mqttAndroidClient.getClientId();
    }
}
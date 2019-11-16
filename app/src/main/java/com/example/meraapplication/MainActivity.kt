package com.example.meraapplication

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.*
import java.nio.ByteBuffer
import java.util.*
import java.util.UUID.randomUUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.ArrayList
import org.eclipse.paho.client.mqttv3.MqttMessage
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory


class MainActivity : AppCompatActivity() {

    val tag = "MERA_APP"
    val tagTcp = "MERA_APP_TCP"
    val tagPublish = "PUBLISH_TCP"
    val filters = ArrayList<ScanFilter>(1)
    val uid = ParcelUuid.fromString("0000feaa-0000-1000-8000-00805f9b34fb")
    val filter = ScanFilter.Builder().setServiceUuid(uid).build()
    val builderScanSettings = ScanSettings.Builder()
    val publishTopic = "iot-hub-kzn-marat-mikhail"
    val serverUri = "tcp://broker.hivemq.com:1883"
    val subscriptionTopic = "iot-hub-kzn-marat-mikhail-receive'"
    private var isConnected = false
    val gson = Gson()

    lateinit var mqttAndroidClient: MqttAndroidClient
    lateinit var coordinatesService: CoordinatesService

    val timer = Timer()
    val coordinatesTimer = Timer()
    val messageMap = ConcurrentHashMap<Int, JsonObject>()
    lateinit var retrofit: Retrofit


    private val bluetoothAdapter: BluetoothAdapter? by lazy(LazyThreadSafetyMode.NONE) {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val timerTaks = object : TimerTask() {
        override fun run() {
            roomView.setPoint(0F, 0F)
            if (isConnected){
                publishMessage(gson.toJson(messageMap))
                calculateDistance(messageMap)
            }
        }

    }

    private fun calculateDistance(messageMap: ConcurrentHashMap<Int, JsonObject>) {

    }

    private val coordinatesTask = object : TimerTask() {
        override fun run() {
            GlobalScope.launch {
                val response = coordinatesService.getCoordinates()
                if(response.isSuccessful){
                    response.body()?.let { data ->
                        GlobalScope.launch(Dispatchers.Main) {
                            roomView.setPoint(data.x.toFloat(), data.y.toFloat())
                        }
                    }
                }
            }
        }

    }

    private fun getClient(): OkHttpClient {
        val interceptor = HttpLoggingInterceptor()
        interceptor.level = HttpLoggingInterceptor.Level.BODY
        return OkHttpClient
            .Builder()
            .addInterceptor(interceptor)
            .build()
    }

    val COORDINATES_HOST = "https://example.com/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        retrofit = Retrofit.Builder()
            .baseUrl(COORDINATES_HOST)
            .client(getClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        coordinatesService = retrofit.create(CoordinatesService::class.java)

        filters.add(filter)
        builderScanSettings.setScanMode(ScanSettings.SCAN_MODE_BALANCED)
        builderScanSettings.setReportDelay(0)

        val helper = PermissionHelper(this)
        if (!helper.hasPermission()) {
            helper.getPermissions()
        } else {
            searchDevices()
        }


        val clientId = randomUUID().toString()
        Log.d(tagTcp, "clientId $clientId")
        mqttAndroidClient = MqttAndroidClient(applicationContext, serverUri, clientId)
        mqttAndroidClient.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String) {
                isConnected = true
                if (reconnect) {
                    Log.d(tagTcp, "Reconnected to : $serverURI")
                    // Because Clean Session is true, we need to re-subscribe
                    //subscribeToTopic()
                } else {
                    Log.d(tagTcp, "Connected to: $serverURI")
                    subscribeToTopic()
                }
            }

            override fun connectionLost(cause: Throwable) {
                isConnected = false
                Log.d(tagTcp, "The Connection was lost.")
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                Log.d(tagTcp, "topic $topic incoming message: " + String(message.payload))
            }

            override fun deliveryComplete(token: IMqttDeliveryToken) {

            }
        })

        val mqttConnectOptions = MqttConnectOptions()
        mqttConnectOptions.isAutomaticReconnect = true
        mqttConnectOptions.isCleanSession = false
        mqttConnectOptions.connectionTimeout = 300
        mqttConnectOptions.keepAliveInterval = 300

        try {
            mqttAndroidClient.connect(mqttConnectOptions, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken) {
                    val disconnectedBufferOptions = DisconnectedBufferOptions()
                    disconnectedBufferOptions.isBufferEnabled = true
                    disconnectedBufferOptions.bufferSize = 100
                    disconnectedBufferOptions.isPersistBuffer = false
                    disconnectedBufferOptions.isDeleteOldestMessages = false
                    mqttAndroidClient.setBufferOpts(disconnectedBufferOptions)
                    Log.d(tagTcp, "onSuccess: $serverUri")
                }

                override fun onFailure(asyncActionToken: IMqttToken, exception: Throwable) {
                    Log.d(tagTcp, "Failed to connect to: $serverUri")
                }
            })


        } catch (ex: MqttException) {
            Log.e(tagTcp, "init error $ex")
            ex.printStackTrace()
        }

        timer.schedule(timerTaks, 0, 500)
        coordinatesTimer.schedule(coordinatesTask, 0, 1_000)


    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>, grantResults: IntArray
    ) {
        when (requestCode) {
            PermissionHelper.PERMISSIONS_REQUEST_LOCATION -> {
                val hasPermission = grantResults.isNotEmpty() &&
                        grantResults[0] == PackageManager.PERMISSION_GRANTED
                if (hasPermission) {
                    searchDevices()
                }
                return
            }
        }
    }

    private val searchCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            //Log.d(tag, "serviceData ${result?.scanRecord?.serviceData}")
            if(isConnected){

                result?.scanRecord?.serviceData?.let { data ->
                    val key = data.keys.iterator().next()
                    val element =  data[key]
                    element?.let { it ->
                        val beaconId = ByteBuffer.wrap(byteArrayOf(it[12], it[13])).short.toInt()
                        val xCord = ByteBuffer.wrap(byteArrayOf(it[14], it[15])).short.toInt()
                        val yCord = ByteBuffer.wrap(byteArrayOf(it[16], it[17])).short.toInt()
                        val rssi = result.rssi
                        val rssCalib = it[1].toInt() - 41
                        val obj = JsonObject()
                        obj.addProperty("beaconId", beaconId)
                        obj.addProperty("xCord", xCord)
                        obj.addProperty("yCord", yCord)
                        obj.addProperty("rssi", rssi)
                        obj.addProperty("rssCalib", rssCalib)
                        messageMap.put(beaconId, obj)
                    }


                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSearching()
        mqttAndroidClient.disconnect()
    }

    private fun searchDevices() {
        bluetoothAdapter?.bluetoothLeScanner?.startScan(
            filters,
            builderScanSettings.build(),
            searchCallback
        )
    }

    private fun stopSearching() {
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(searchCallback)
    }

    private fun publishMessage(payload: String) {
        try {
            val message = MqttMessage()
            message.payload = payload.toByteArray()
            mqttAndroidClient.publish(publishTopic, message)
            Log.d(tagPublish, "publishMessage success $payload")
        } catch (e: MqttException) {
            Log.e(tagTcp, "publishMessage error $e")
        }
    }

    fun subscribeToTopic(){
        try {
            mqttAndroidClient.subscribe(subscriptionTopic, 0, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(tagTcp, "subscribeToTopic onSuccess $asyncActionToken")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.d(tagTcp, "subscribeToTopic onFailure")
                }

            })

        } catch (ex: MqttException){
            Log.e(tagTcp, "Exception whilst subscribing $ex")
            ex.printStackTrace()
        }
    }
}

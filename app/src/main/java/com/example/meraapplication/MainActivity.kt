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
import kotlin.math.abs
import kotlin.math.pow


class MainActivity : AppCompatActivity() {

    val tag = "MERA_APP"
    val tagTcp = "MERA_APP_TCP"
    val tagPublish = "PUBLISH_TCP"
    val tagDistance= "DISTANCE_BEACON"
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
            calculatePosition(messageMap)
            if (isConnected){
                publishMessage(gson.toJson(messageMap))
            }
        }

    }
    /*
    obj.addProperty("xCord", xCord)
                        obj.addProperty("yCord", yCord)
                        obj.addProperty("rssi", rssi)
                        obj.addProperty("rssCalib", rssCalib)
     */

    private fun calculatePosition(messageMap: ConcurrentHashMap<Int, JsonObject>) {

        if(messageMap.size >= 3){
            val items = messageMap.values.sortedBy { abs(it["rssi"].asInt*-1 ) }
            val obj1 = items[0]
            val x1 = obj1["xCord"].asFloat
            val y1 = obj1["yCord"].asFloat
            val r1 = getRadius(obj1["rssCalib"].asInt, obj1["rssi"].asInt)
            val obj2 = items[1]

            val x2 = obj2["xCord"].asFloat
            val y2 = obj2["yCord"].asFloat
            val r2 = getRadius(obj2["rssCalib"].asInt, obj2["rssi"].asInt)
            val obj3 = items[2]
            val x3 = obj3["xCord"].asFloat
            val y3 = obj3["yCord"].asFloat
            val r3 = getRadius(obj3["rssCalib"].asInt, obj3["rssi"].asInt)

           /* Log.d(tagDistance, "rss1 ${obj1["rssi"].asInt}")
            Log.d(tagDistance, "rss2 ${obj2["rssi"].asInt}")
            Log.d(tagDistance, "rss3 ${obj3["rssi"].asInt}")
*/
            val result = trackPhone(
                x1,y1,r1,
                x2,y2,r2,
                x3,y3,r3)
            GlobalScope.launch(Dispatchers.Main) {
                roomView.setPoint(result.first, result.second)
            }
            Log.d(tagDistance, "position result ${result.first} ${result.second}")
        }
    }

    private fun getRadius(rssiCalib: Int, rssiRaw: Int): Float{
        //hack
        val rssi = Math.abs(rssiRaw)
        if (30 < rssi && rssi <= 40)
            return 0.5.toFloat()
        else if (40 < rssi && rssi <= 50)
            return 1.toFloat()
        else if (50 < rssi && rssi <= 60)
            return 2.5.toFloat()
        else if (60 < rssi && rssi <= 70)
            return 5.toFloat()
        else if (70 < rssi && rssi <= 80)
            return 7.5.toFloat()
        else if (80 < rssi && rssi <= 90)
            return 17.5.toFloat()
        else if (90 < rssi && rssi <= 100)
            return 30.toFloat()

        return 30.toFloat()
    }

    private fun getRadius1(rssiCalib: Int, rssiRaw: Int): Float{
        return Math.pow(10.0, ((rssiCalib - rssiRaw) / (10 * 2)).toDouble()).toFloat()
    }

    private fun trackPhone(x1:Float,y1:Float,r1:Float,
                   x2:Float,y2:Float,r2:Float,x3:Float,
                   y3:Float,r3:Float): Pair<Float,Float> {
        val A = 2 * x2 - 2 * x1
        val B = 2 * y2 - 2 * y1
        val C = r1.pow(2)-r2.pow(2)-x1.pow(2)+x2.pow(2)-y1.pow(2)+y2.pow(2)
        val D = 2 * x3 - 2 * x2
        val E = 2 * y3 - 2 * y2
        val F = r2.pow(2)-r3.pow(2)-x2.pow(2)+x3.pow(2)-y2.pow(2)+y3.pow(2)
        val val1 = if((E * A - B * D).toInt() == 0) 1.0.toFloat() else (E * A - B * D)
        val val2 = if((B * D - A * E).toInt() == 0) 1.0.toFloat() else (B * D - A * E)
        val x = (C * E ) / val1
        val y = (C * D ) /val2
        return Pair(Math.abs(x/200),Math.abs(y/200))
    }

    private val coordinatesTask = object : TimerTask() {
        override fun run() {
            GlobalScope.launch {
                /*val response = coordinatesService.getCoordinates()
                if(response.isSuccessful){
                    response.body()?.let { data ->
                        GlobalScope.launch(Dispatchers.Main) {
                            roomView.setPoint(data.x.toFloat(), data.y.toFloat())
                        }
                    }
                }*/
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

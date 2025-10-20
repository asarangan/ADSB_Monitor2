package org.sarangan.adsb2

import android.content.res.ColorStateList
import android.graphics.Color
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.MulticastLock
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
//import com.example.myapplication.R
import org.sarangan.adsb2.R
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.util.Formatter
import java.util.Timer
import java.util.TimerTask
import kotlin.Exception

const val TAG:String = "ADSB"

val stratusDataOpen: ByteArray = byteArrayOf(  //Byte sequence to switch Stratus to GDL-90
    0xC2.toByte(),
    0x53.toByte(),
    0xFF.toByte(),
    0x56.toByte(),
    0x01.toByte(),
    0x01.toByte(),
    0x6E.toByte(),
    0x37.toByte()
)
val stratusDataClose: ByteArray =
    byteArrayOf(  //Byte sequence to switch Stratus to Foreflight-only Mode
        0xC2.toByte(),
        0x53.toByte(),
        0xFF.toByte(),
        0x56.toByte(),
        0x01.toByte(),
        0x00.toByte(),
        0x6D.toByte(),
        0x36.toByte()
    )

//This is the number of packets received for each type of data
val packetCount = mutableMapOf<String,Int>("heartbeat" to 0, "gps" to 0, "traffic" to 0, "ahrs" to 0, "uplink" to 0)

//This is a timer (ie stopwatch) for each data type. The indicator turns green when a data is received, and it stays for
//2 seconds, and then turns red. The timer is needed to count for 2 seconds and set it to red when the timer expires.
//When a new data is received the timer is reset, and starts from zero again.
val timers = mutableMapOf<String,Timer>("heartbeat" to Timer(), "gps" to Timer(), "traffic" to Timer(), "ahrs" to Timer(), "uplink" to Timer())


var switchChange: Boolean = true //This is just a status change boolean. It is set when the switch is moved.
// We want to run UDPSend when the app starts (with the switch in the ON position)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG,"Main Activity onCreate - start")
        super.onCreate(savedInstanceState)

        //Set the layout from XML
        setContentView(R.layout.layout)

        //Get the resource ID for the toggle switch
        val mySwitch = findViewById<Switch>(R.id.switchToggle)

        //Start with the switch set to Open GDL mode
        mySwitch.isChecked = true

        //Set the switch color depending on the state
        mySwitch.thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),
            intArrayOf(Color.GREEN, Color.RED))

        //Any time the switch moved, we set a boolean variable to true (so that the corresponding UDP packet can be sent)
        mySwitch.setOnCheckedChangeListener{_, isChecked ->
            switchChange = true
            Log.d(TAG,"Switch changed")
        }





        ///////////////////////////////////////////////////////////////////////////////////
        //Setup the transmit socket & wifi
        val socketOut = DatagramSocket()
        val wifiManager: WifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
//        val lock: MulticastLock = wifiManager.createMulticastLock("Log_Tag")
//        lock.acquire() //Multicast lock is needed because some devices block UDP broadcast
        val longIP = wifiManager.connectionInfo.ipAddress.toLong()
        val byteIP = BigInteger.valueOf(longIP).toByteArray().reversedArray()
        var ipAddress:String?
        try{    //If the wifi is off, we will get 0 as the address, and the hostAddress function will crash
            ipAddress = InetAddress.getByAddress(byteIP).hostAddress
        }
        catch (e: Exception){//When it crashes, we set the ip to Loopback and flag an IP error
            ipAddress = "127.0.0.1"
            findViewById<TextView>(R.id.textViewError).text = getString(R.string.IPError)
        }
        Log.d(TAG,"IP Address is $ipAddress")
        val lastDotIndex = ipAddress.lastIndexOf(".")
        val ipAddress255 = ipAddress.substring(0, lastDotIndex)+".255"
        Log.d(TAG,"Sending IP Address is $ipAddress255")



        //UDP transmit function
        fun udpSend() {
            //C2 53 FF 56 01 01 6E 37 (UDP Port 41500) to switch to Open Mode
            //C2 53 FF 56 01 00 6D 36  (UDP Port 41500) to switch to Closed Mode
            Log.d(TAG,"UDPSend start")
            val sendData : ByteArray
            if (mySwitch.isChecked) {
                sendData = stratusDataOpen
                Log.d(TAG,"UDPSend - openData")
            } else {
                sendData = stratusDataClose
                Log.d(TAG,"UDPSend - closeData")
            }

            val sendPacket = DatagramPacket(
                sendData,
                sendData.size,
                InetAddress.getByName(ipAddress255),
                41500       //The FF to Open-mode command is sent on port 41500
            )
            socketOut.send(sendPacket)
            Log.d(TAG,"UDPSend exit")
        }//End of UDP Send function




        ///////////////////////////////////////////////////////////////////////////////////
        //Setup the receive socket
        var socketIn = DatagramSocket()
        socketIn.soTimeout = 2000 //When Stratus is in Foreflight mode, it transmits in a different port, so this timeout is necessary to prevent a stall
        //The try below is just to check if port 4000 is accessible. It doesn't really read any data
        try {
            socketIn = MulticastSocket(4000) //Most ADSB transmit GDL-90 on port 4000. Otherwise this may need to be changed.
        } catch (e: java.lang.Exception) {
            findViewById<TextView>(R.id.textViewError).text = resources.getString(R.string.Port4000Error)
        }

        val buffer = ByteArray(64)  //We read 64 bytes at a time
        val packetIn = DatagramPacket(buffer, buffer.size)  //This is where the datagram packet will be read in



        //Now start the thread - this runs repeatedly on a separate thread.
        Thread{
            Log.d(TAG,"Thread launch")
            while (true){
                //Log.d(TAG,"Thread start")
                try{
                    //Whenever the switch is moved, we send the appropriate open or close UDP packet
                    //But we should do this only once per toggle so we don't flood the network, so we set the boolean to false after one send
                    if (switchChange){
                        udpSend()
                        switchChange = false
                    }

                //green is the function that turns the circle to green to indicate a valid data packet. It also increments
                //the data packet counter (using the token). IdText is the resource id, referenced as R.id.whatever
                //idLight is the resource id of the button (light) which is referenced as R.id.whatever
                //When we set the light to green, we also start a timer for 2000ms. When it expires, the light automatically turns red.
                //We need a separate timer for each light, which was declared as a mutableMap array globally.
                //When a new data is received, there doesn't seem to be a way to reset the timer. We need to cancel (delete) it, and then create a new timer.
                fun green(idText: Int, idLight:Int, token:String){
                    //Increment the data count by 1
                    packetCount[token] = packetCount.getOrDefault(token,0) + 1
                    findViewById<TextView>(idText).text = packetCount[token].toString()
                    findViewById<Button>(idLight).setBackgroundResource(R.drawable.circle_green)
                    //The following is the task object that gets executed when the timer expires.
                    val timerTask = object : TimerTask() {
                        override fun run() {
                            findViewById<Button>(idLight).setBackgroundResource(R.drawable.circle_red)
                        }
                    }
                    timers[token]?.cancel() //Because we are not able to reset the old timer, we cancel (delete) it.
                    timers[token] = Timer() //Then we create a new timer
                    timers[token]?.schedule(timerTask, 2000)    //And we assign the task to that timer and set the 2 seconds on it.
                }



                    try {
                        socketIn.soTimeout = 2000   //The previously set timeout gets erased after one attempt, so we need to set it again.
                        socketIn.receive(packetIn)  //Read the 64 byte packet
                    }
                    catch (e: SocketTimeoutException){
                        Log.d(TAG,"Socket time out")
                    }


                    if (packetIn.data[0].toInt() == 0x7e) { // If byte 0 is 0x7e that means it the flag byte of a data frame

                        // If the flag byte is 0, that means it is a HEARTBEAT signal
                        if (packetIn.data[1].toInt() == 0) {
                            //Changes to the UI cannot be done from a thread, so we need to specify run on UI
                            //This will change the light to green, start the timer and also update the data count.
                            this@MainActivity.runOnUiThread(Runnable {
                                green(R.id.textViewHeartbeatCount, R.id.heartbeat_light, "heartbeat")
                            })}

                        // If the flag byte is 10, that means it is a GPS signal (also known as OWNSHIP signal)
                        if (packetIn.data[1].toInt() == 10) {
                            this@MainActivity.runOnUiThread(Runnable {
                                green(R.id.textViewGPSCount, R.id.gps_light, "gps")
                            })}

                        // If the flag byte is 20, that means it is a TRAFFIC signal
                        if (packetIn.data[1].toInt() == 20) {
                            this@MainActivity.runOnUiThread(Runnable {
                                green(R.id.textViewTrafficCount, R.id.traffic_light, "traffic")
                            })}

                        // Stratux uses 0x4C as the flag for its AHRS signal, presumably other ADSB receivers as well.
                        // AHRS data flag is not described in the GDL-90 specification.
                        if (packetIn.data[1].toInt() == 0x4C) {
                            this@MainActivity.runOnUiThread(Runnable {
                                green(R.id.textViewAHRSCount, R.id.ahrs_light, "ahrs")
                            })}

                        // Foreflight always wants to be different, so it uses 0x4C followed by another byte that is set to 1.
                        // Again, AHRS data flag is not described in the GDL-90 specification.
                        if ((packetIn.data[1].toInt() == 0x4C) && (packetIn.data[2].toInt() == 1)) {
                            this@MainActivity.runOnUiThread(Runnable {
                                green(R.id.textViewAHRSCount, R.id.ahrs_light, "ahrs")
                            })}

                        // If the flag byte is 7, that means it is a tower UPLINK
                        if (packetIn.data[1].toInt() == 7) {
                            this@MainActivity.runOnUiThread(Runnable {
                                green(R.id.textViewUplinkCount, R.id.uplink_light, "uplink")
                            })}
                    }

                }
                catch (e: Exception){//If we get to this catch, something went wrong. Not sure
                    Log.d(TAG,"Exception Error")
                    this@MainActivity.runOnUiThread({findViewById<TextView>(R.id.textViewError).text = getString(
                        R.string.runTimeError)})
                }
                //Log.d(TAG,"Thread end")
            }
        }.start()

        Log.d(TAG,"Main Activity onCreate - exit")

    }
}

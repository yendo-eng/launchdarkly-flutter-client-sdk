package com.launchdarkly.launchdarkly_flutter_client_sdk

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.annotation.NonNull
import androidx.annotation.VisibleForTesting;
import com.launchdarkly.sdk.EvaluationReason
import com.launchdarkly.sdk.LDContext
import com.launchdarkly.sdk.LDValue
import com.launchdarkly.sdk.LDValueType
import com.launchdarkly.sdk.android.Components
import com.launchdarkly.sdk.android.ConnectionInformation
import com.launchdarkly.sdk.android.FeatureFlagChangeListener
import com.launchdarkly.sdk.android.LDAllFlagsListener
import com.launchdarkly.sdk.android.LDClient
import com.launchdarkly.sdk.android.LDConfig
import com.launchdarkly.sdk.android.LDConfig.Builder.AutoEnvAttributes
import com.launchdarkly.sdk.android.LDFailure
import com.launchdarkly.sdk.android.LaunchDarklyException
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.Registrar
import kotlinx.coroutines.*
import java.util.concurrent.Future

public class LaunchdarklyFlutterClientSdkPlugin: FlutterPlugin, MethodCallHandler {
  private lateinit var channel : MethodChannel
  private lateinit var application: Application
  private lateinit var flagChangeListener: FeatureFlagChangeListener
  private lateinit var allFlagsListener: LDAllFlagsListener
  private val defaultScope = CoroutineScope(Dispatchers.Default)

  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    application = flutterPluginBinding.applicationContext as Application
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "launchdarkly_flutter_client_sdk")
    setupListeners()
    channel.setMethodCallHandler(this)
  }

  private fun callFlutter(method: String, arguments: Any?) {
      // invokeMethod must be called on main thread
      if (Looper.myLooper() == Looper.getMainLooper()) {
        channel.invokeMethod(method, arguments)
      } else {
        // Call ourselves on the main thread
        Handler(Looper.getMainLooper()).post { callFlutter(method, arguments) }
      }
  }

  private fun setupListeners() {
    flagChangeListener = FeatureFlagChangeListener { channel.invokeMethod("handleFlagUpdate", it) }
    allFlagsListener = LDAllFlagsListener {
      callFlutter("handleFlagsReceived", it)
    }
  }

  companion object {
    // This static function is optional and equivalent to onAttachedToEngine. It supports the old
    // pre-Flutter-1.12 Android projects. You are encouraged to continue supporting
    // plugin registration via this function while apps migrate to use the new Android APIs
    // post-flutter-1.12 via https://flutter.dev/go/android-project-migration.
    @JvmStatic
    fun registerWith(registrar: Registrar) {
      val channel = MethodChannel(registrar.messenger(), "launchdarkly_flutter_client_sdk")
      val plugin = LaunchdarklyFlutterClientSdkPlugin()
      plugin.application = registrar.context() as Application
      plugin.channel = channel
      plugin.setupListeners()
      channel.setMethodCallHandler(plugin)
    }

    private inline fun <reified T> whenIs(value: Any?, call: (value: T) -> Unit) {
      if (value is T) {
        call(value as T)
      }
    }

    fun configFromMap(map: Map<String, Any>): LDConfig {
      val autoEnvAttributes = if (map["autoEnvAttributes"] is Boolean && map["autoEnvAttributes"] as Boolean) {
        AutoEnvAttributes.Enabled
      } else {
        AutoEnvAttributes.Disabled
      }

      val configBuilder = LDConfig.Builder(autoEnvAttributes).generateAnonymousKeys(true)
      return internalConfigFromMap(map, configBuilder)
    }

    @VisibleForTesting
    fun internalConfigFromMap(map: Map<String, Any>, configBuilder: LDConfig.Builder): LDConfig {
      // We want this Flutter plugin to support omitting keys from anonymous contexts.  The Android
      // SDK requires us turn this on for it to operate.  iOS handles it automatically, which
      // is why this is only appearing here in the Android plugin code.
      whenIs<String>(map["mobileKey"]) { configBuilder.mobileKey(it) }
      whenIs<Int>(map["maxCachedContexts"]) { configBuilder.maxCachedContexts(it) }
      whenIs<Boolean>(map["offline"]) { configBuilder.offline(it) }
      whenIs<Boolean>(map["disableBackgroundUpdating"]) { configBuilder.disableBackgroundUpdating(it) }
      whenIs<Boolean>(map["evaluationReasons"]) { configBuilder.evaluationReasons(it) }
      whenIs<Boolean>(map["diagnosticOptOut"]) { configBuilder.diagnosticOptOut(it) }

      // Only provide application info if at least one property is present
      var applicationInfoIsNonEmpty = false
      val infoBuilder = Components.applicationInfo().apply {
        whenIs<String>(map["applicationId"]) { this.applicationId(it); applicationInfoIsNonEmpty = true}
        whenIs<String>(map["applicationName"]) { this.applicationName(it); applicationInfoIsNonEmpty = true }
        whenIs<String>(map["applicationVersion"]) { this.applicationVersion(it); applicationInfoIsNonEmpty = true }
        whenIs<String>(map["applicationVersionName"]) { this.applicationVersionName(it); applicationInfoIsNonEmpty = true }
      }
      if (applicationInfoIsNonEmpty) {
        configBuilder.applicationInfo(infoBuilder)
      }

      configBuilder.serviceEndpoints(
              Components.serviceEndpoints().apply {
                whenIs<String>(map["pollUri"]) { this.polling(it) }
                whenIs<String>(map["eventsUri"]) { this.events(it) }
                whenIs<String>(map["streamUri"]) { this.streaming(it) }
              }
      )

      configBuilder.dataSource(
              if (map["stream"] is Boolean && !(map["stream"] as Boolean)) {
                // use polling data source if stream is false
                Components.pollingDataSource().apply {
                  whenIs<Int>(map["pollingIntervalMillis"]) { this.pollIntervalMillis(it) }
                  whenIs<Int>(map["backgroundPollingIntervalMillis"]) { this.backgroundPollIntervalMillis(it) }
                }
              } else {
                // use streaming data source by default (including if stream is absent)
                Components.streamingDataSource().apply {
                  whenIs<Int>(map["backgroundPollingIntervalMillis"]) { this.backgroundPollIntervalMillis(it) }
                }
              }
      )

      configBuilder.events(
              Components.sendEvents().apply {
                whenIs<Int>(map["eventsCapacity"]) { this.capacity(it) }
                whenIs<Int>(map["eventsFlushIntervalMillis"]) { this.flushIntervalMillis(it) }
                whenIs<Int>(map["diagnosticRecordingIntervalMillis"]) { this.diagnosticRecordingIntervalMillis(it) }

                if (map["allAttributesPrivate"] is Boolean) {
                  this.allAttributesPrivate(map["allAttributesPrivate"] as Boolean)
                }
                whenIs<List<*>>(map["privateAttributes"]) {
                  val privateAttrs = ArrayList<String>()
                  for (name in it) {
                    if (name is String) {
                      privateAttrs.add(name)
                    }
                  }
                  this.privateAttributes(*privateAttrs.toTypedArray())
                }
              }
      )

      configBuilder.http(
              Components.httpConfiguration().apply {
                whenIs<Int>(map["connectionTimeoutMillis"]) { this.connectTimeoutMillis(it) }
                whenIs<Boolean>(map["useReport"]) { this.useReport(it) }
                if (map["wrapperName"] is String && map["wrapperName"] is String) {
                  this.wrapper(map["wrapperName"] as String, map["wrapperName"] as String)
                }
              }
      )

      return configBuilder.build()
    }

    /**
     * Creates a [LDContext] from the provided list of contexts provided, each in map form.
     *
     * @param list - the list of dictionaries of serialized contexts.  Note that the format
     * of this dict is unique to the Flutter MethodChannel because it has kind and key as neighbors
     * at the same level in the dict.
     */
    @Suppress("UNCHECKED_CAST")
    fun contextFrom(list: List<Map<String, Any>>): LDContext {
      val multiBuilder = LDContext.multiBuilder()
      list.forEach {

        // create a copy since we'll be making some changes on the fly
        val context = it.toMutableMap()

        // If key is omitted we need to insert a placeholder and mark the context as anonymous.
        // This is because the Android SDK requires a key.  iOS doesn't have to do this because
        // it handles omitted keys more elegantly.
        //
        // Some extra info:  Other code in this plugin turns on the Android SDKs generateAnonymousKeys functionality.
        if (context["key"] == null) {
          context["key"] = "__LD_PLACEHOLDER_KEY__"
          context["anonymous"] = true
        }

        val contextBuilder = LDContext.builder(context["key"] as? String);
        for (entry in context) {
          // ignore _meta
          if (entry.key == "_meta") {
            continue
          }

          contextBuilder.set(entry.key, valueFromBridge(entry.value))
        }

        // grab private attributes out of _meta field if they are there
        val metaMap = (context["_meta"] as? Map<String, Any>) ?: emptyMap()
        val privateAttrs = (metaMap["privateAttributes"] as? ArrayList<String>) ?: ArrayList()
        contextBuilder.privateAttributes(*privateAttrs.toTypedArray()) // * is spread operator

        multiBuilder.add(contextBuilder.build());
      }

      return multiBuilder.build();
    }

    fun valueFromBridge(dyn: Any?): LDValue {
      when (dyn) {
        null -> return LDValue.ofNull()
        is Boolean -> return LDValue.of(dyn)
        is Number -> return LDValue.of(dyn.toDouble())
        is String -> return LDValue.of(dyn)
        is ArrayList<*> -> {
          val arrBuilder = LDValue.buildArray()
          dyn.forEach {
            arrBuilder.add(valueFromBridge(it))
          }
          return arrBuilder.build()
        }
        else -> {
          val objBuilder = LDValue.buildObject()
          (dyn as HashMap<*, *>).forEach {
            objBuilder.put(it.key as String, valueFromBridge(it.value))
          }
          return objBuilder.build()
        }
      }
    }

    fun valueToBridge(ldValue: LDValue): Any? {
      when (ldValue.type) {
        null, LDValueType.NULL -> return null
        LDValueType.BOOLEAN -> return ldValue.booleanValue()
        LDValueType.NUMBER -> return ldValue.doubleValue()
        LDValueType.STRING -> return ldValue.stringValue()
        LDValueType.ARRAY -> {
          val res = ArrayList<Any?>()
          ldValue.values().forEach {
            res.add(valueToBridge(it))
          }
          return res
        }
        LDValueType.OBJECT -> {
          val res = HashMap<String, Any?>()
          ldValue.keys().forEach {
            res[it] = valueToBridge(ldValue.get(it))
          }
          return res
        }
      }
    }

    fun detailToBridge(value: Any?, variationIndex: Int?, reason: EvaluationReason?): Any? {
      val res = HashMap<String, Any?>()
      res["value"] = value
      res["variationIndex"] = variationIndex
      val reasonRes = HashMap<String, Any?>()
      reasonRes["kind"] = reason?.kind?.name
      when (reason?.kind) {
        EvaluationReason.Kind.RULE_MATCH -> {
          reasonRes["ruleIndex"] = reason.ruleIndex
          reasonRes["ruleId"] = reason.ruleId
          reasonRes["inExperiment"] = reason.isInExperiment
        }
        EvaluationReason.Kind.PREREQUISITE_FAILED -> {
          reasonRes["prerequisiteKey"] = reason.prerequisiteKey
        }
        EvaluationReason.Kind.FALLTHROUGH -> {
          reasonRes["inExperiment"] = reason.isInExperiment
        }
        EvaluationReason.Kind.ERROR -> {
          reasonRes["errorKind"] = reason.errorKind.name
        }
        else -> {}
      }
      res["reason"] = reasonRes
      return res
    }

    fun ldFailureToBridge(failure: LDFailure?): Any? {
      if (failure == null) return null
      val res = HashMap<String, Any?>()
      res["message"] = failure.message
      res["failureType"] = failure.failureType.name
      return res
    }

    fun connectionInformationToBridge(connectionInformation: ConnectionInformation?): Any? {
      if (connectionInformation == null) return null
      val res = HashMap<String, Any?>()
      res["connectionState"] = connectionInformation.connectionMode.name
      res["lastFailure"] = ldFailureToBridge(connectionInformation.lastFailure)
      res["lastSuccessfulConnection"] = connectionInformation.lastSuccessfulConnection
      res["lastFailedConnection"] = connectionInformation.lastFailedConnection
      return res
    }
  }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    when (call.method) {
      "start" -> {
        val ldConfig: LDConfig = configFromMap(call.argument("config")!!)
        val ldContext: LDContext = contextFrom(call.argument("context")!!)

        var completion: Future<*>
        try {
          val instance = LDClient.get()
          // We've already initialized the native SDK so just switch to the new context.
          completion = instance.identify(ldContext)
        } catch (ignored: LaunchDarklyException) {
          // We have not already initialized the native SDK.
          completion = LDClient.init(application, ldConfig, ldContext)
          LDClient.get().registerAllFlagsListener(allFlagsListener)
        }

        defaultScope.launch {
          withContext(Dispatchers.IO) {
            try {
              completion.get()
            } finally {
              callFlutter("completeStart", null)
            }
          }
        }

        result.success(null)
      }
      "identify" -> {
        defaultScope.launch {
          withContext(Dispatchers.IO) {
            val ldContext: LDContext = contextFrom(call.argument("context")!!)
            val completion = LDClient.get().identify(ldContext)
            try {
              completion.get()
            } finally {
              result.success(null)
            }
          }
        }
      }
      "track" -> {
        val data = valueFromBridge(call.argument("data"))
        val metric: Double? = call.argument("metricValue")
        if (metric == null) {
          LDClient.get().trackData(call.argument("eventName"), data)
        } else {
          LDClient.get().trackMetric(call.argument("eventName"), data, metric)
        }
        result.success(null)
      }
      "boolVariation" -> {
        val evalResult = LDClient.get().boolVariation(call.argument("flagKey")!!, call.argument("defaultValue")!!)
        result.success(evalResult)
      }
      "boolVariationDetail" -> {
        val evalResult = LDClient.get().boolVariationDetail(call.argument("flagKey")!!, call.argument("defaultValue")!!)
        result.success(detailToBridge(evalResult.value, evalResult.variationIndex, evalResult.reason))
      }
      "intVariation" -> {
        val evalResult: Int = LDClient.get().intVariation(call.argument("flagKey")!!, call.argument("defaultValue")!!)
        result.success(evalResult)
      }
      "intVariationDetail" -> {
        val evalResult = LDClient.get().intVariationDetail(call.argument("flagKey")!!, call.argument("defaultValue")!!)
        result.success(detailToBridge(evalResult.value, evalResult.variationIndex, evalResult.reason))
      }
      "doubleVariation" -> {
        val evalResult = LDClient.get().doubleVariation(call.argument("flagKey")!!, call.argument("defaultValue")!!)
        result.success(evalResult)
      }
      "doubleVariationDetail" -> {
        val evalResult = LDClient.get().doubleVariationDetail(call.argument("flagKey")!!, call.argument("defaultValue")!!)
        result.success(detailToBridge(evalResult.value, evalResult.variationIndex, evalResult.reason))
      }
      "stringVariation" -> {
        val evalResult = LDClient.get().stringVariation(call.argument("flagKey")!!, call.argument("defaultValue"))
        result.success(evalResult)
      }
      "stringVariationDetail" -> {
        val evalResult = LDClient.get().stringVariationDetail(call.argument("flagKey")!!, call.argument("defaultValue"))
        result.success(detailToBridge(evalResult.value, evalResult.variationIndex, evalResult.reason))
      }
      "jsonVariation" -> {
        val defaultValue = valueFromBridge(call.argument("defaultValue"))
        val evalResult = LDClient.get().jsonValueVariation(call.argument("flagKey")!!, defaultValue)
        result.success(valueToBridge(evalResult))
      }
      "jsonVariationDetail" -> {
        val defaultValue = valueFromBridge(call.argument("defaultValue"))
        val evalResult = LDClient.get().jsonValueVariationDetail(call.argument("flagKey")!!, defaultValue)
        result.success(detailToBridge(valueToBridge(evalResult.value), evalResult.variationIndex, evalResult.reason))
      }
      "allFlags" -> {
        var allFlagsBridge = HashMap<String, Any?>()
        val allFlags = LDClient.get().allFlags()
        allFlags.forEach {
          allFlagsBridge[it.key] = valueToBridge(it.value)
        }
        result.success(allFlagsBridge)
      }
      "flush" -> {
        LDClient.get().flush()
        result.success(null)
      }
      "setOnline" -> {
        val online: Boolean? = call.argument("online")
        if (online == true) {
          LDClient.get().setOnline()
        } else if (online == false) {
          LDClient.get().setOffline()
        }
      }
      "isOffline" -> {
        result.success(LDClient.get().isOffline)
      }
      "getConnectionInformation" -> {
        result.success(connectionInformationToBridge(LDClient.get().connectionInformation))
      }
      "startFlagListening" -> {
        LDClient.get().registerFeatureFlagListener(call.arguments as String, flagChangeListener)
        result.success(null)
      }
      "stopFlagListening" -> {
        LDClient.get().unregisterFeatureFlagListener(call.arguments as String, flagChangeListener)
        result.success(null)
      }
      "close" -> {
        LDClient.get().close()
        result.success(null)
      }
      else -> {
        result.notImplemented()
      }
    }
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
  }
}

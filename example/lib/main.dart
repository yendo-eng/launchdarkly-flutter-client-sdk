import 'package:flutter/material.dart';
import 'dart:async';
import 'dart:convert';

import 'package:launchdarkly_flutter_client_sdk/launchdarkly_flutter_client_sdk.dart';

void main() {
  runApp(MyApp());
}

class MyApp extends StatefulWidget {
  @override
  _MyAppState createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  String typeDropdown = 'Boolean';
  String userKey = '';
  String evalKey = '';
  String evalResult = '';
  bool offline = false;

  @override
  void initState() {
    super.initState();
    initPlatformState();
  }

  // Platform messages are asynchronous, so we initialize in an async method.
  Future<void> initPlatformState() async {
    final LDConfig ldConfig = LDConfigBuilder('MOBILE_KEY')
        .build();
    final LDUser ldUser = LDUserBuilder('user key').build();

    await LaunchdarklyFlutterClientSdk.start(ldConfig, ldUser);

    // If the widget was removed from the tree while the asynchronous platform
    // message was in flight, we want to discard the reply rather than calling
    // setState to update our non-existent appearance.
    if (!mounted) return;
  }

  void evaluate() async {
    switch (typeDropdown) {
      case 'Boolean':
        var result = await LaunchdarklyFlutterClientSdk.boolVariation(evalKey, false);
        setState(() { evalResult = result.toString(); });
        break;
      case 'Integer':
        var result = await LaunchdarklyFlutterClientSdk.intVariation(evalKey, 0);
        setState(() { evalResult = result.toString(); });
        break;
      case 'Float':
        var result = await LaunchdarklyFlutterClientSdk.doubleVariation(evalKey, 0);
        setState(() { evalResult = result.toString(); });
        break;
      case 'String':
        var result = await LaunchdarklyFlutterClientSdk.stringVariation(evalKey, "");
        setState(() { evalResult = result; });
        break;
      case 'Json':
        var result = await LaunchdarklyFlutterClientSdk.jsonVariation(evalKey, LDValue.buildObject().build());
        setState(() { evalResult = json.encode(result.codecValue()); });
        break;
      default:
        break;
    }
  }

  void track() async {
    await LaunchdarklyFlutterClientSdk.track(evalKey);
  }

  void identify() async {
    final LDUser ldUser = LDUserBuilder(userKey).build();
    await LaunchdarklyFlutterClientSdk.identify(ldUser);
  }

  void flush() async {
    await LaunchdarklyFlutterClientSdk.flush();
  }

  void toggleOffline(bool offline) async {
    setState(() { this.offline = offline; });
    await LaunchdarklyFlutterClientSdk.setOnline(!offline);
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(title: const Text('LaunchDarkly Example')),
        body: Container(
          padding: EdgeInsets.all(8.0),
          child: Column(
            children: [
              Row(crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                Expanded(child:
                  TextField(onChanged: (text) { setState(() { evalKey = text; }); },
                      decoration: InputDecoration.collapsed(hintText: 'Key', border: UnderlineInputBorder()))),
                Padding(padding: EdgeInsets.symmetric(horizontal: 4.0)),
                DropdownButton<String>(
                  value: typeDropdown,
                  isDense: true,
                  onChanged: (String newValue) {
                    setState(() {
                      typeDropdown = newValue;
                    });
                  },
                  items: ['Boolean', 'Integer', 'Float', 'String', 'Json']
                    .map((String value) {
                      return DropdownMenuItem<String>(value: value, child: Text(value));
                  }).toList()
                )
              ]),
              Padding(padding: EdgeInsets.symmetric(vertical: 4.0)),
              Row(children: [
                Expanded(child: RaisedButton(child: Text('Evaluate'), onPressed: evaluate)),
                Padding(padding: EdgeInsets.symmetric(horizontal: 4.0)),
                Expanded(child: RaisedButton(child: Text('Track'), onPressed: track))
              ]),
              Divider(),
              Row(children: [Text("$evalResult", textAlign: TextAlign.start)]),
              Spacer(),
              Divider(),
              Row(children: [Expanded(child: Text('Connection Status\n\n\n', style: TextStyle(color: Colors.grey)))]),
              Divider(),
              Row(children: [
                Expanded(child: TextField(onChanged: (text) { setState(() { userKey = text; }); },
                    decoration: InputDecoration.collapsed(hintText: 'User Key', border: UnderlineInputBorder()))),
                Padding(padding: EdgeInsets.symmetric(horizontal: 4.0)),
                RaisedButton(child: Text('Identify'), onPressed: identify)
              ]),
              Row(children: [
                RaisedButton(child: Text('Flush'), onPressed: flush),
                Spacer(),
                Text('Offline'),
                Switch(value: offline, onChanged: toggleOffline)
              ])
              ])
          ),
        ),
      );
  }
}

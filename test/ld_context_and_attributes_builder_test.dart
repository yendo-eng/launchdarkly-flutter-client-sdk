import 'package:flutter_test/flutter_test.dart';
import 'package:launchdarkly_flutter_client_sdk/launchdarkly_flutter_client_sdk.dart';

void main() {
  test('context builder simple case', () {
    LDContextBuilder builder = LDContextBuilder();
    builder.kind('user', 'uuid').name('Todd');
    builder.kind('company', 'key').name('LaunchDarkly');
    LDContext context = builder.build();
    List<dynamic> output = context.toCodecValue();

    List<dynamic> expectedOutput = [
      {
        'kind': 'user',
        'key': 'uuid',
        'name': 'Todd',
        '_meta': {}
      },
      {
        'kind': 'company',
        'key': 'key',
        'name': 'LaunchDarkly',
        '_meta': {}
      }
    ];

    expect(output, equals(expectedOutput));
  });

  test('context builder anonymous', () {
    LDContextBuilder builder = LDContextBuilder();
    builder.kind('user', 'uuid').name('Todd');
    builder.kind('company', 'key').name('LaunchDarkly').anonymous(true);
    LDContext context = builder.build();
    List<dynamic> output = context.toCodecValue();

    List<dynamic> expectedOutput = [
      {
        'kind': 'user',
        'key': 'uuid',
        'name': 'Todd',
        '_meta': {}
      },
      {
        'kind': 'company',
        'key': 'key',
        'anonymous': true,
        'name': 'LaunchDarkly',
        '_meta': {}
      }
    ];

    expect(output, equals(expectedOutput));
  });

  test('context builder with custom type', () {
    LDContextBuilder builder = LDContextBuilder();
    builder
        .kind('user', 'uuid')
        .name('Todd')
        .set(
            'level1',
            LDValue.buildObject()
                .addValue('level2',
                    LDValue.buildObject().addNum('aNumber', 7).build())
                .build())
        .set('customType', LDValue.ofString('customValue'));
    builder.kind('company', 'key').name('LaunchDarkly');
    LDContext context = builder.build();
    List<dynamic> output = context.toCodecValue();

    List<dynamic> expectedOutput = [
      {
        'kind': 'user',
        'key': 'uuid',
        'name': 'Todd',
        'level1': {
          'level2': {
            'aNumber': 7,
          }
        },
        'customType': 'customValue',
        '_meta': {}
      },
      {
        'kind': 'company',
        'key': 'key',
        'name': 'LaunchDarkly',
        '_meta': {}
      }
    ];

    expect(output, equals(expectedOutput));
  });

  test('setting reserved _meta is ignored', () {
    LDContextBuilder builder = LDContextBuilder();
    builder
        .kind('user')
        .name('Todd')
        .set('_meta', LDValue.ofBool(false));

    LDContext context = builder.build();
    List<dynamic> output = context.toCodecValue();

    List<dynamic> expectedOutput = [
      {
        'kind': 'user',
        'name': 'Todd',
        '_meta': {}
      },
    ];

    expect(output, equals(expectedOutput));
  });

  test('calling kind multiple times', (){
    LDContextBuilder builder = LDContextBuilder();
    builder
        .kind('user')
        .set('counter', LDValue.ofNum(0));

    LDContext context1 = builder.build();
    List<dynamic> output1 = context1.toCodecValue();

    List<dynamic> expectedOutput1 = [
      {
        'kind': 'user',
        'counter': 0,
        '_meta': {}
      },
    ];

    expect(output1, equals(expectedOutput1));

    // now call kind again but this time with a different key and update attr
    builder.kind('user', 'aKey')
        .set('counter', LDValue.ofNum(1));

    LDContext context2 = builder.build();
    List<dynamic> output2 = context2.toCodecValue();

    List<dynamic> expectedOutput2 = [
      {
        'kind': 'user',
        'key': 'aKey',
        'counter': 1,
        '_meta': {}
      },
    ];

    expect(output2, equals(expectedOutput2));

  });

  test('dropping invalid not required attributes', () {
    LDContextBuilder builder = LDContextBuilder();
    builder
        .kind('user')
        .set('keepMe', LDValue.ofNum(0))
        .set('name', LDValue.ofNum(0)) // should be dropped
        .set('anonymous', LDValue.ofNum(0)) // should be dropped
        .set('', LDValue.ofNum(0)) // should be dropped
        .set('_meta', LDValue.ofNum(0)); // should be dropped

    LDContext context = builder.build();
    List<dynamic> output = context.toCodecValue();

    List<dynamic> expectedOutput = [
      {
        'kind': 'user',
        'keepMe': 0,
        '_meta': {}
      },
    ];

    expect(output, equals(expectedOutput));
  });

  test('private attributes basic case', () {
    LDContextBuilder builder = LDContextBuilder();
    builder
        .kind('user', 'uuid')
        .name('Todd')
        .set("address", LDValue.ofString("Main Street"))
        .privateAttributes(["name", "address"]);

    builder.kind('company').name('LaunchDarkly');
    LDContext context = builder.build();
    List<dynamic> output = context.toCodecValue();

    List<dynamic> expectedOutput = [
      {
        'kind': 'user',
        'key': 'uuid',
        'name': 'Todd',
        'address': 'Main Street',
        '_meta': {
          'privateAttributes': ['name', 'address']
        }
      },
      {
        'kind': 'company',
        'name': 'LaunchDarkly',
        '_meta': {}
      }
    ];

    expect(output, equals(expectedOutput));
  });
}

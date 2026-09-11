# polkadot-js for dApp calldata decoding

Polkadot dApps will call into our wallet via `signPayload`, which is defined [here](https://github.com/polkadot-js/api/blob/master/packages/types/src/types/extrinsic.ts#L32).

The thing of note here is that `method` is the field which describes the transaction the dApp is attempting to have us sign so they can submit it to the blockchain. Decoding an method call like this has us parse and handle the entire type registry built from runtime metadata defined [here](https://docs.rs/frame-metadata/latest/frame_metadata/). Once the type registry has been built and is sufficiently queryable, we then must incrementally decode the method and translate each complete sub-component into a human-readable representation.

To this end, we can simply use the `@polkadot/types` package side-loaded via a hidden iframe which points to a `chrome-untrusted` resource.

This is a complete example of our intended usage of `@polkadot/types`:
```js
import { Metadata, TypeRegistry } from '@polkadot/types'

const registry = new TypeRegistry()

// `hex` is the runtime hex from the state_getMetadata RPC call.
const metadata = new Metadata(registry, hex)
registry.setMetadata(metadata)
registry.setChainProperties(
    registry.createType('ChainProperties', {
        ss58Format,
        tokenDecimals,
        tokenSymbol,
    }),
)

// `method` is provided to us via the dApp.
const call = registry.createType('Call', method)
console.log(JSON.stringify(call.toHuman(), null, 2))
```

## Implementation

* The browser will hold a mapping: `(genesis_hash, spec_version) -> runtime_metadata`, where `runtime_metadata` is the untouched hex string returned from the `state_getMetadata` RPC call.
  * The browser will be responsible for ensuring the string is hex but will not attempt to decode the data in-process.
* The browser will fetch the chain properties via the `system_properties` RPC call which provides the required ss58 prefix, token decimals and symbol. This will be kept alongside the runtime metadata in memory.
* When a dApp calls our `signPayload` method, we will render the confirmation panel which lazily loads a hidden `<iframe/>` pointing to a `chrome-untrusted` resource page which loads the compiled JS bundle containing `@polkadot/types`, similar to how Trezor works in the wallet now.
* The browser will hold a `mojom::Remote` connected to its corresponding `mojom::Receiver` in the `chrome-untrusted://` page which it uses to pass over the runtime metadata and chain properties.
* The JS in the untrusted frame will take this metadata, build the entire type registry with it and decode the provided `method` and assuming success, will hand back the `call.toHuman()` output, which is just a JSON blob.
* From here, the browser process will now be in possession of a safely decoded method, which looks roughly:
  ```
  {
    "args": {
      "dest": {
        "Id": "129sSFxPxi77AVnxZRaUMEoLQ8PtVH6SWAKsdk253ku7MxsY"
      },
      "value": "1,234"
    },
    "method": "transferKeepAlive",
    "section": "balances"
  }
  ```
  when given the input: `0x0a030032fffa4729e6d1447a62c39b997ad75ddbaf80455ea44719b58ee4c03f6a14024913`.
* This will enable us to either display the call data as-is in the worst case (it's still largely understandable) or we can incrementally probe the JSON ourselves and massage the output into however we wish.
* The browser can then display this in the confirmation panel for the signing request and once the user confirms, we can begin with the normal signature flow for Polkadot extrinsics.

This means that we must audit `@polkadot/types` before its inclusion into the source tree.

## Trade-Offs and Caveats

Pros:
* Completely memory-safe.
* Trivially handles the entire type registry, which includes decoding complex multi-layered Calls such as batched ones.
* Already supports human-friendly representation for all methods and calls by using type registry information.

Cons:
* Must create type registry every time the confirmation panel is loaded and the browser calls into it.
  * This function is still relatively fast though, easily completing in less than 500ms on a good machine. mojom helps us amortize the cost of copying.

The only potential danger with this choice of implementation is that it's slow, but this can perhaps be addressed if enough users report sluggishness or an unusable dApp.

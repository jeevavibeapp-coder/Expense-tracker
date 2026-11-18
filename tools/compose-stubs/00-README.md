# Compose / Material3 / Room type-check harness

Hand-written declarations of the third-party surface the Android sources use,
transcribed from the pinned versions:

    Compose BOM 2024.09.03  ->  compose ui/foundation 1.7.x, material3 1.3.0
    Room 2.6.1, activity-compose 1.9.2, coroutines 1.8.1, Kotlin 2.1

They exist because the Android SDK is not installable in this environment and
Gradle therefore cannot run, while `kotlinc-jvm` can. Compiling the real app
sources against these declarations catches unresolved references, wrong
arity, wrong *parameter names*, type mismatches, non-exhaustive `when`, and
every call into `:core`.

Parameter names, order and defaults are the whole point: a call written with
a named argument the real API does not have must fail here, or the harness is
worse than useless. When a stub disagrees with the real API, the stub is the
bug.

## What this harness CANNOT catch

- Anything the Compose compiler plugin enforces. `@Composable` here is an
  ordinary annotation with no plugin behind it, so calling a composable from
  a non-composable context, or from a lambda that is not composable, is NOT
  reported. Same for `@ReadOnlyComposable` and restricted-scope rules.
- Anything KSP generates: Room's `@Query` SQL is not parsed, column names are
  not checked against the entities, and the generated `SpendDatabase_Impl`
  does not exist. A DAO that compiles here can still fail `kspDebugKotlin`.
- Resources (`R.*`), the manifest, ProGuard, and anything resolved at runtime.
- Runtime behaviour of any kind.

Concretely: a green run here means "the Kotlin type system is satisfied", not
"this builds an APK".

## What was verified by running it

Deliberately-wrong probe code was compiled against these stubs and each of
the following produced an error, so the harness is not silently permissive:

| probe | result |
|---|---|
| `Text(colour = …)` — misspelled parameter | `no parameter with name 'colour'` |
| `OutlinedTextField(helperText = …)` — invented parameter | reported |
| `KeyboardOptions(autoCorrect = …)` — the pre-1.7 name | reported |
| `Modifier.weight(1f)` inside `Box` (valid in Row/Column) | `unresolved reference 'weight'` |
| `Alignment.Vertical` passed as `horizontalArrangement` | type mismatch |
| `items(list)` without the `lazy.items` import | resolves to `items(count: Int)`, mismatch |
| a colour role that does not exist on `ColorScheme` | unresolved |
| non-exhaustive `when` over an enum | reported |
| `Flow.collectAsState(initialValue = …)` (it is `initial`) | reported |
| `Slider(range = …)` (it is `valueRange`) | reported |
| `Icon(…)` without the required `contentDescription` | no applicable candidate |
| wrong argument type into a `:core` function | type mismatch |
| `delay(…)` outside a coroutine | reported |
| experimental material3 API without `@OptIn` | opt-in error |

`Modifier.weight(1f)` inside `Column` was checked to still compile, so the
scope receivers are discriminating rather than just absent.

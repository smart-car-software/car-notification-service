# API для приложений-клиентов

Сервис (`com.smartcar.notify`) — единственное место, где всплывашки показываются на приборке
Monjaro и на главном экране. Клиент не рисует ничего сам: он отправляет запрос, сервис ставит его в
очередь, показывает в свою очередь и присылает ответ обратно.

## Запрос

Явный broadcast на `com.smartcar.notify/.RequestReceiver`:

| Extra | Тип | |
|---|---|---|
| `id` | String | **обязательно**. Уникален для того, о чём спрашиваем; начинайте с имени своего пакета (`com.barrieropener.app:open:5`). Повторная отправка того же `id` обновляет запрос на его месте (на экране или в очереди), а не ставит второй |
| `type` | String | `info`, `question` или `list` |
| `title` | String | **обязательно**, одна строка |
| `text` | String | необязательно, вторая строка (тусклее) |
| `options` | String[] | `question`: 1–4 ответа (⏮ ⏭ — выбор, ⏯ — нажать); `list`: 1–50 строк (колёсико громкости — прокрутка, ⏯ — выбрать); `info`: не передавать |
| `selected` | int | выбранный сначала ответ/строка, по умолчанию 0 |
| `timeout_ms` | long | сколько висит после показа. По умолчанию `info` — 8 с, остальное — 5 мин (больше 5 мин не бывает) |
| `expire_ms` | long | сколько может ждать в очереди, пока не станет неактуален (считается от получения). По умолчанию без ограничения |
| `sound` | boolean | звук при появлении |
| `reply` | PendingIntent | куда прислать результат. Для `info` можно не передавать. На targetSdk 31+ — `FLAG_MUTABLE` (сервис дописывает extras) |

Действие `com.smartcar.notify.action.SHOW` — показать, `com.smartcar.notify.action.CANCEL` (с `id`) —
убрать из очереди или с экрана (придёт `cancelled`).

## Ответ

В `reply` приходят extras:

| Extra | |
|---|---|
| `id` | id запроса |
| `result` | `answered` — выбран `index`; `dismissed` — `info` показан до конца (время, ⏯ или нажатие); `timeout` — показан, но не отвечен; `expired` — не показан, ждал дольше `expire_ms`; `cancelled` — снят через CANCEL или сервис остановлен; `invalid` — запрос с ошибкой (`error`); `unavailable` — показать нечем (нет разрешения «поверх других приложений»): спросите по-своему |
| `index` | int: выбранный ответ/строка, `-1` для всего, кроме `answered` |
| `option` | String: текст выбранного |
| `error` | String: почему `invalid` |

Ровно один ответ на каждый запрос с `reply` (повторная отправка того же `id` заменяет запрос — ответ
придёт один, на последний `reply`).

Если сервис не установлен (`PackageManager.getPackageInfo("com.smartcar.notify", 0)` бросает
`NameNotFoundException`), клиенту стоит показать вопрос по-своему.

## Клиентский класс (скопировать к себе)

```java
final class NotifyClient {
    static final String PACKAGE = "com.smartcar.notify";
    static final String RECEIVER = "com.smartcar.notify.RequestReceiver";
    static final String ACTION_SHOW = "com.smartcar.notify.action.SHOW";
    static final String ACTION_CANCEL = "com.smartcar.notify.action.CANCEL";

    static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** reply: PendingIntent.getBroadcast(c, requestCode, explicitIntentToYourReceiver,
     *  FLAG_UPDATE_CURRENT | FLAG_MUTABLE) */
    static void question(Context c, String id, String title, String[] answers,
                         long expireMs, boolean sound, PendingIntent reply) {
        Intent i = base(ACTION_SHOW, id);
        i.putExtra("type", "question");
        i.putExtra("title", title);
        i.putExtra("options", answers);
        if (expireMs > 0) i.putExtra("expire_ms", expireMs);
        i.putExtra("sound", sound);
        i.putExtra("reply", reply);
        c.sendBroadcast(i);
    }

    static void cancel(Context c, String id) {
        c.sendBroadcast(base(ACTION_CANCEL, id));
    }

    private static Intent base(String action, String id) {
        Intent i = new Intent(action);
        i.setClassName(PACKAGE, RECEIVER);
        i.putExtra("id", id);
        return i;
    }
}
```

Ресивер ответа можно объявить `android:exported="false"`: `PendingIntent` выполняется от имени
клиента. Клиенту с targetSdk 30+ обязательно нужен в манифесте
`<queries><package android:name="com.smartcar.notify" /></queries>`: без него на Android 11+ сервис
для клиента невидим — и `getPackageInfo`, и сам broadcast молча не сработают.

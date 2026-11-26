# ИИ-агент поиска ответов по корпоративным документам (RAG)

Агент отвечает на вопросы сотрудников по внутренним документам компании
(регламенты, приказы, инструкции, договоры): кто подписывает договор,
какой регламент действует, кто отвечает за этап. Информация ищется
семантически — по смыслу, а не по ключевым словам, с указанием источника.

Стек: **Java 21 · Spring Boot 3.5 · Spring AI 1.1 · PostgreSQL 17 + pgvector · OpenAI-совместимый API** (эмбеддинги `bge-m3-rus1`, генерация `deepseek/deepseek-v4-flash-0731`).

## Архитектура

Два контура (по схеме Spring AI RAG):

```
Офлайн (ETL)   файл → TikaDocumentReader → TokenTextSplitter → EmbeddingModel (bge-m3-rus1)
               → PgVectorStore → PostgreSQL (vector_store, HNSW-индекс) + каталог documents

Рантайм (RAG)  вопрос → EmbeddingModel → поиск top-5 в pgvector (косинусная близость)
               → промпт «контекст + инструкция + вопрос» → deepseek-v4-flash → ответ + источники
```

Ключевое правило агента (анти-галлюцинация): **отвечать только по найденному
контексту; если ответа нет — прямо об этом сказать**. Порог сходства 0.6.

## Подготовка окружения

Есть вариант без локальной установки PostgreSQL и JDK — раздел «Запуск в Docker»
ниже: приложение и база с pgvector поднимаются через `docker compose`.
Дальше описана локальная установка.

1. **PostgreSQL 17** с расширением **pgvector** — поднят как **отдельный
   инстанс** (служба Windows `pg-rag`, автозапуск), чтобы не зависеть от
   основного сервера на 5432:

   | Параметр | Значение |
   |---|---|
   | Каталог данных | `C:\Users\kysik\pg-rag` |
   | Порт | `5433` |
   | Логин / пароль | `postgres` / `postgres` (локальный демо-инстанс) |
   | БД | `rag_docs` с расширением `vector` 0.8.7 |

   Управление службой: `net start pg-rag` / `net stop pg-rag`,
   или через services.msc.

2. **Токен OpenAI-совместимого API** — адрес `https://plusvibeapi.ru/v1`.
   Задаётся переменной окружения `AI_API_KEY` или строкой `AI_API_KEY=...`
   в файле `.env` в каталоге проекта (без кавычек; в git файл не попадает).
   Без токена приложение не стартует. Если провайдер выдаёт для эмбеддингов
   отдельный ключ — задайте `AI_EMBEDDING_API_KEY`; пока он пуст, используется
   общий. Модели прописаны в `application.yml`: чат
   `deepseek/deepseek-v4-flash-0731`, эмбеддинги `bge-m3-rus1`.

3. Пароль postgres: переменная окружения `DB_PASSWORD`
   (или правка `application.yml`).

4. **Логин и пароль входа**: API и демо-страница закрыты HTTP Basic.
   Задаются переменными `APP_AUTH_USERNAME` (по умолчанию `admin`) и
   `APP_AUTH_PASSWORD` (окружение или строка `APP_AUTH_PASSWORD=...`
   в `.env`). Без пароля приложение не стартует.

## Запуск

```bat
cd rag-agent
mvnw.cmd spring-boot:run        (Linux/macOS: ./mvnw spring-boot:run)
```

Демо-интерфейс: http://localhost:8090/

## Аутентификация

Все запросы — и API, и страница — закрыты HTTP Basic (realm `rag-agent`).
При открытии http://localhost:8090/ браузер сам показывает диалог входа;
после него учётные данные прикладываются к запросам автоматически и
интерфейс работает как раньше.

- Логин и пароль: `APP_AUTH_USERNAME` / `APP_AUTH_PASSWORD` (см. «Подготовка окружения»).
- Пример вызова API из консоли: `curl -u admin:пароль http://localhost:8090/api/kb`.
- Скриптам `tools/*.py` учётные данные передаются переменными окружения
  `RAG_USERNAME` и `RAG_PASSWORD`, адрес приложения — `RAG_BASE_URL`
  (по умолчанию `http://localhost:8090`); в Bruno учётные данные заданы
  в окружении Local.

Ограничения Basic: пароль передаётся в base64 без шифрования (для localhost
приемлемо, для сети нужен HTTPS); «выход» — закрытие браузера, учётные данные
держатся в его кэше; логин один на всех, защиты от перебора нет.

## Запуск в Docker

Приложение и PostgreSQL 17 с расширением pgvector поднимаются одной командой.
Нужен Docker с плагином Compose; JDK, Maven и локальный PostgreSQL не требуются.

```bat
cd rag-agent
docker compose up --build
```

Первый запуск дольше остальных: собирается образ приложения (Maven скачивает
зависимости). Остановить — `Ctrl+C` или `docker compose down`.

| Сервис | Образ | Адрес |
|---|---|---|
| `app` | собирается из `Dockerfile` (сборка на Maven + JDK 21, запуск на JRE 21) | http://localhost:8090/ |
| `db` | `pgvector/pgvector:pg17` | `localhost:5433`, база `rag_docs` — только с этой машины |

- База создаётся при первом запуске на пустом томе `pg-rag-data`; расширение
  `vector` включает скрипт `docker/postgres/init/01-pgvector.sql`.
  Приложение стартует только после готовности базы (`healthcheck`).
- Данные хранятся в томе: `docker compose down` их сохраняет,
  `docker compose down -v` удаляет вместе с базой.
- Порты `8090` и `5433` совпадают с локальной установкой. Перед запуском
  остановите локальное приложение и службу `pg-rag` (`net stop pg-rag`),
  иначе контейнеры не смогут занять порты.
- Токен API берётся из `.env` (`AI_API_KEY=...`). Без него контейнер `app`
  не запускается — в его журнале `OpenAI API key must be set`. Так же не
  запустится контейнер и без `APP_AUTH_PASSWORD` (сообщение о незаданном
  пароле входа).

Значения задаются в файле `.env` рядом с `docker-compose.yml`
(`AI_API_KEY` обязателен, остальные необязательны):

| Переменная | По умолчанию | Что задаёт |
|---|---|---|
| `APP_PORT` | `8090` | порт приложения на машине |
| `DB_PORT` | `5433` | порт базы на машине |
| `DB_NAME` / `DB_USER` / `DB_PASSWORD` | `rag_docs` / `postgres` / `postgres` | база и учётная запись; приложение получает их автоматически |
| `AI_API_KEY` | — | токен OpenAI-совместимого API (чат и эмбеддинги); без него приложение не запускается |
| `AI_EMBEDDING_API_KEY` | пусто | отдельный ключ для эмбеддингов, если провайдер выдаёт его отдельно от общего |
| `APP_AUTH_USERNAME` | `admin` | логин Basic-аутентификации API и демо-страницы |
| `APP_AUTH_PASSWORD` | — | пароль Basic-аутентификации; без него приложение не запускается |

Например, чтобы Docker-вариант работал рядом с локальной установкой:

```dotenv
APP_PORT=8091
DB_PORT=5434
```

`DB_NAME`, `DB_USER` и `DB_PASSWORD` применяются только при создании базы.
Чтобы изменить их для существующей базы, выполните `docker compose down -v`
(данные базы будут удалены).

### Перенос данных из локальной базы

База в контейнере изначально пуста. Чтобы перенести в неё базы знаний из
службы `pg-rag` (командная строка Windows):

```bat
cd rag-agent
set PGPASSWORD=postgres
"%ProgramFiles%\PostgreSQL\17\bin\pg_dump.exe" -h localhost -p 5433 -U postgres -Fc -f "%TEMP%\rag_docs.dump" rag_docs
net stop pg-rag
docker compose up -d db
docker compose cp "%TEMP%\rag_docs.dump" db:/tmp/rag_docs.dump
docker compose exec -T db pg_restore -U postgres -d rag_docs --clean --if-exists --no-owner /tmp/rag_docs.dump
docker compose up -d --build app
```

## Деплой на Dockhost

`dockhost.yaml` описывает тот же стек для платформы [Dockhost](https://docs.dockhost.ru/manual):
базу `pgvector/pgvector:pg17` на сетевом диске и приложение, которое платформа собирает из
Git-репозитория после каждого push и публикует на домене `*.ds.dockhost.io` по HTTPS.
Нужны [консольная утилита Dockhost](https://docs.dockhost.ru/cli/install) и проект на платформе.

Платформа собирает из репозитория GitHub `https://github.com/XieGAMES/ragprototype.git`
(приватный). В нём лежит весь родительский репозиторий, приложение — в каталоге `rag-agent/`,
поэтому изменения заливаются из корня родительского репозитория:

```bat
git remote add github https://github.com/XieGAMES/ragprototype.git     :: один раз
git add rag-agent
git commit -m "rag-agent: ..."
git push github main
```

### Этап 1 — база, репозиторий, домен

```bat
cd rag-agent
dockhost project use <проект>
dockhost compose apply
```

Будут созданы переменная проекта `DB_PASSWORD` (пароль генерируется и выводится командой,
список переменных — `dockhost environment variable list`), диск `pgdata`, база `db`,
репозиторий `app` и домен; платформа начнёт первую сборку. Репозиторий приватный, а утилита
не передаёт логин и пароль из `dockhost.yaml`, поэтому доступ задаётся отдельно — токеном
GitHub с правом чтения репозитория:

```bat
dockhost repository update app --url https://github.com/XieGAMES/ragprototype.git --branch main --username XieGAMES --password <токен>
dockhost repository build app
```

### Этап 2 — приложение и маршрут

Когда сборка пройдёт, создайте секреты проекта (в файле их нет) и посмотрите имя образа,
которое платформа присвоила контейнеру `app`:

```bat
dockhost environment variable set --name AI_API_KEY --value <ключ>
dockhost environment variable set --name AI_EMBEDDING_API_KEY --value <ключ>
dockhost environment variable set --name APP_AUTH_PASSWORD --value <пароль входа>
dockhost compose dump --file project.yaml
```

Затем впишите это имя (`containers` → `app` → `image` в `project.yaml`) в `containers.app.image`
файла `dockhost.yaml`, уберите `#` у блоков с пометкой «Этап 2» и примените файл ещё раз:

```bat
dockhost compose apply
```

- Адрес приложения — домен, созданный на этапе 1; вход — `APP_AUTH_USERNAME` / `APP_AUTH_PASSWORD`.
- База наружу не опубликована. Подключение с машины: `dockhost network port-forward --project <проект> db 5435:5432`,
  затем `psql -h localhost -p 5435 -U postgres rag_docs` (пароль — `DB_PASSWORD`).
- `dockhost compose delete` удаляет всё описанное в файле, включая диск с базой знаний.

## API

| Метод | Путь | Назначение |
|---|---|---|
| POST | `/api/ask` | `{"question": "..."}` → ответ + источники |
| POST | `/api/documents` | multipart: `file`, `docType`, `title` — загрузка в базу знаний |
| GET | `/api/documents` | каталог загруженных документов |
| DELETE | `/api/documents?file=...` | удалить документ из базы знаний |

Все эндпоинты требуют Basic-аутентификацию — см. раздел «Аутентификация».

## Как проверить (сценарий демо)

1. Загрузить 3–4 документа: регламент договорной работы, приказ о матрице
   ответственности, инструкция по командировкам, шаблон агентского договора.
2. Спросить: «Кто подписывает агентский договор?» → ответ со ссылкой
   на пункт регламента и процентом сходства.
3. Спросить что-то, чего в документах нет → агент честно отвечает,
   что не нашёл (не выдумывает).
4. Загрузить обновлённую версию регламента под тем же именем → старая
   версия заменяется, агент отвечает по актуальной.

## Настройки

`src/main/resources/application.yml`: модель чата и эмбеддингов, размеры
`top-k` / порога сходства (`app.rag.*`), строка подключения к БД.

Адрес API и модели — в `application.yml` (`spring.ai.openai.*`). `base-url`
указывается без `/v1`: Spring AI сам добавляет `/v1/chat/completions` и
`/v1/embeddings`.

При смене модели эмбеддингов ничего не перезагружать вручную не нужно. При
первом обращении к каждой базе знаний приложение сверяет модель, которой
получены её векторы, с текущей и при расхождении заново считает эмбеддинги
по сохранённому тексту чанков (`EmbeddingReindexer`) — исходные файлы не
нужны. Первый запрос по базе после смены модели поэтому дольше обычного.
Размерность векторов определяется по ответу API.

Порог сходства `app.rag.similarity-threshold` (`0.35`) подбирался под прежние
модели эмбеддингов: если ответы тянут лишние источники или теряют нужные,
его стоит пересмотреть.

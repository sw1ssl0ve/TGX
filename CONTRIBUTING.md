# Contributing to TGX

Thank you for helping improve TGX. Changes to the app are welcome, including bug fixes, networking improvements, refactoring, tests, and documentation.

## About TGX and contributor credit

TGX was created by **sw1ssl0ve**. Its technical background includes ideas and compatible approaches from [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy), including MTProto over WebSocket and Cloudflare proxy-domain routing. TGX is a separate Android application with its own Kotlin implementation, interface, and Android service lifecycle. It is not an official Flowseal product. Please preserve this attribution when changing related documentation or code.

Contributors are welcome to improve the implementation. Their work should be credited through the relevant pull request, commit history, and GitHub contributor records. Contributor credit recognizes a person's work; it does not change who created TGX as a project, make the contributor a maintainer or project owner, or grant authority over official releases.

Contributors retain authorship and copyright in their original contributions. By submitting work for inclusion in TGX, you agree that the contribution may be distributed under the MIT License in [LICENSE](LICENSE). No separate copyright transfer is requested.

## Before opening a pull request

- Check existing issues and pull requests to avoid duplicating work.
- For a substantial feature or architectural change, open an issue first to discuss the approach.
- For bug reports, include the Android version, device or emulator, steps to reproduce, expected behavior, and actual behavior. Remove personal data, proxy Secrets, credentials, and other sensitive information from logs and screenshots.

## Pull requests

1. Fork the repository and create a branch from main.
2. Keep each pull request focused on one change.
3. Explain the problem, the change, user-visible effects, and any relevant limitations.
4. Add or update unit tests when changing behavior. Tests are especially useful for MTProto parsing, transport and cryptography, domain-manifest validation, network changes, and Android service lifecycle.
5. Update the Russian and English README files when user-facing behavior or setup instructions change. Keep other translations aligned where practical.
6. Submit the pull request against main from your fork. The project owner reviews and decides whether to merge it. Maintainers may request changes or decline a pull request.

## Build and checks

TGX uses Kotlin, Gradle, and Android Gradle Plugin. The CI workflow uses JDK 25 and Android SDK platform 37.0 (Android 17 preview).

From the repository root, run the same checks as CI:

~~~bash
./gradlew --no-daemon testDebugUnitTest assembleDebug lintDebug
~~~

On Windows, run gradlew.bat with the same task names. If you cannot install the Android 17 preview SDK locally, mention that in your pull request; CI will run the required checks.

Tests and debug builds do not need release signing credentials.

## Networking, privacy, and attribution

TGX handles Telegram connections sent to its local proxy; it is not a system-wide VPN. Avoid adding claims that TGX guarantees access, anonymity, or operation on every network. Changes to routing, proxy Secrets, network requests, or diagnostic logging should explain their privacy and failure behavior.

Preserve applicable third-party notices and attribution, including references to Flowseal/tg-ws-proxy where relevant. Explain the purpose of new dependencies in your pull request.

## Official releases and credentials

Contributors may propose changes throughout the application through pull requests. Official signed APKs, version tags, and GitHub Releases remain under project-owner control. Do not add, request, expose, or use release signing credentials. Do not create official release tags or publish releases. Changes to release workflows require explicit project-owner review.

---

# Участие в разработке TGX

Спасибо, что помогаете улучшать TGX. Приветствуются изменения приложения: исправления ошибок, улучшения сетевой работы, рефакторинг, тесты и документация.

## О проекте и признании вклада

TGX создан **sw1ssl0ve**. Технологическая основа проекта включает идеи и совместимые подходы [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy), в том числе MTProto через WebSocket и маршрутизацию через Cloudflare-проксируемые домены. TGX — отдельное Android-приложение с собственной Kotlin-реализацией, интерфейсом и управлением Android-сервисом. Это не официальный продукт Flowseal. Сохраняйте эту атрибуцию в связанных с этими подходами изменениях кода и документации.

Контрибьюторы могут предлагать улучшения реализации. Их вклад следует отмечать в соответствующих pull request, истории коммитов и списке участников GitHub. Признание конкретного вклада не меняет того, кто создал TGX как проект, не делает контрибьютора мейнтейнером или владельцем проекта и не даёт полномочий выпускать официальные версии.

Контрибьютор сохраняет авторство и авторские права на собственные оригинальные изменения. Отправляя работу для включения в TGX, вы соглашаетесь, что она может распространяться по лицензии MIT из файла [LICENSE](LICENSE). Отдельная передача авторских прав не требуется.

## Перед pull request

- Проверьте существующие issues и pull request, чтобы не дублировать работу.
- Перед крупной функцией или архитектурным изменением откройте issue и обсудите подход.
- В сообщении об ошибке укажите версию Android, устройство или эмулятор, шаги воспроизведения, ожидаемое и фактическое поведение. Удалите из логов и скриншотов личные данные, proxy Secret, пароли и другие чувствительные сведения.

## Pull request

1. Создайте fork репозитория и отдельную ветку от main.
2. Старайтесь посвящать один pull request одному изменению.
3. Опишите проблему, изменение, влияние на пользователя и важные ограничения.
4. Добавляйте или обновляйте unit-тесты при изменении поведения. Особенно полезны тесты для разбора MTProto, транспорта и криптографии, проверки доменного манифеста, смены сети и жизненного цикла Android-сервиса.
5. При изменении поведения или пользовательских инструкций обновляйте README на русском и английском. По возможности синхронизируйте остальные переводы.
6. Создайте pull request из своего fork в main. Решение о принятии и объединении принимает владелец проекта. Он может запросить доработки или отклонить pull request.

## Сборка и проверки

TGX использует Kotlin, Gradle и Android Gradle Plugin. В CI используются JDK 25 и Android SDK platform 37.0 (Android 17 preview).

Из корня репозитория запустите те же проверки, что выполняет CI:

~~~bash
./gradlew --no-daemon testDebugUnitTest assembleDebug lintDebug
~~~

В Windows используйте gradlew.bat с теми же задачами. Если вы не можете установить Android 17 preview SDK локально, укажите это в pull request; необходимые проверки выполнит CI.

Для тестов и debug-сборок signing credentials не нужны.

## Сеть, приватность и атрибуция

TGX обрабатывает подключения Telegram, направленные на локальный proxy; это не системный VPN. Не добавляйте обещания, что TGX гарантирует доступ, анонимность или работу в любой сети. В изменениях маршрутизации, proxy Secret, сетевых запросов и диагностических логов описывайте влияние на приватность и поведение при ошибках.

Сохраняйте применимые уведомления и атрибуцию сторонних проектов, в том числе Flowseal/tg-ws-proxy, если они относятся к изменению. Объясняйте назначение новых зависимостей в pull request.

## Официальные релизы и credentials

Контрибьюторы могут предлагать изменения в любой части приложения через pull request. Официальные подписанные APK, версионные теги и GitHub Releases остаются под контролем владельца проекта. Не добавляйте, не запрашивайте, не раскрывайте и не используйте signing credentials релиза. Не создавайте официальные release-теги и не публикуйте релизы. Изменения release workflows требуют явного рассмотрения владельцем проекта.

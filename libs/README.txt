Baritone для клиентской команды #gather
=======================================

Сюда нужно положить ОДИН файл с точным именем:

    libs/baritone-fabric.jar

Если файла нет — build.gradle просто исключит клиентские классы и соберёт
только серверную часть (/gatherbot). Команда #gather при этом недоступна.

Как получить
------------
1) git clone -b 26.1 https://github.com/cabaletta/baritone
2) cd baritone && gradlew build -Pmod_version=1.18.0
3) из baritone/dist/ скопировать baritone-unoptimized-fabric-<hash>.jar
   сюда под именем baritone-fabric.jar

Какой флейвор брать (важно!)
----------------------------
unoptimized-fabric  -> ЭТОТ. Ничего не обфусцировано, атрибуты class-файлов целы.

api-fabric          -> НЕ подходит для компиляции. baritone.api сохранён, но
                       ProGuard (scripts/proguard.pro) не хранит атрибут Exceptions:
                       -keepattributes Signature, *Annotation*, InnerClasses
                       => у ICommand.execute/tabComplete исчезает throws CommandException
                       => javac: "overridden method does not throw CommandException".

standalone-fabric   -> НЕ подходит. Обфусцировано всё, включая baritone.api
                       => javac: "package baritone.api does not exist".

без суффикса лоадера -> launchwrapper-твикер под ванильный клиент, не Fabric-мод.
forge / neoforge     -> другой загрузчик.

В игре (папка mods/)
--------------------
Нужны три вещи: Fabric API, любой рабочий Baritone для Fabric
(unoptimized-fabric удобнее — читаемые стектрейсы; api-fabric тоже подходит,
так как baritone.api в нём сохранён) и наш schematic-gatherer-*.jar.

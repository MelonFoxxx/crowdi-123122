# FakeCrowd (Paper 1.21.10)

Фейкові гравці в табі / автодоповненні /tp, плаваючий онлайн у списку серверів,
чат-боти з самонавчанням (MiniBrain) і розмовними сесіями.

## Збірка
Потрібні JDK 21 і Maven:
    mvn clean package
Готовий файл: target/FakeCrowd.jar -> у папку plugins/

## Залежності на сервері
ProtocolLib 5.4.0 або новіший (для 1.21.10). Без нього плагін не запуститься.

## Команди
/fakecrowd reload  - перечитати config.yml
/fakecrowd status  - хто онлайн з фейків, скільки вивчено фраз
Дозвіл: fakecrowd.admin

## Навчання
Файли plugins/FakeCrowd/learned.txt та corpus.txt створюються самі.
Щоб "обнулити" мозок - зупини сервер і видали ці два файли.

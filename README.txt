FakeCrowd - вбудований "ШІ", без зовнішніх залежностей (крім ProtocolLib на сервері).

ЯК ОТРИМАТИ .jar
 Варіант 1 (без встановлень): залий папку на GitHub -> вкладка Actions -> "Build plugin" -> Artifacts -> FakeCrowd-jar.
 Варіант 2: встанови JDK 21 + Maven, в папці проєкту виконай: mvn clean package
            jar буде в target/FakeCrowd.jar

ВСТАНОВЛЕННЯ: FakeCrowd.jar + ProtocolLib (5.3.0+) у plugins/, перезапуск сервера.

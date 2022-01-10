# DBeaverWeatherTask
REST API written in Java 17 with the use of Tomcat 10, Jakarta EE 9 and MySQL.
The project contains a single end-point "/weather" which returns current weather temperature.

This endpoint uses optional "type" parameter to specify desired data type:
- JSON (used by default)
- XML

Another optional parameter is "scale":
- CEL (Celsius, used by default)
- FAR (Fahrenheit)
- KEL (Kelvin)

*All parameters are case-insensitive*

If there was an error when accessing database or retrieving data from yandex.ru JSON/XML with error message will be sent.

All the code for this can be found in "weather.java" file

@echo off
echo ====================================
echo Compilation de ZombieMod v1.0.1
echo ====================================
echo.
echo Etape 1/3 : Verification de Java...
java -version
echo.
echo Etape 2/3 : Nettoyage des anciens fichiers...
gradlew.bat clean
echo.
echo Etape 3/3 : Compilation du mod...
gradlew.bat build
echo.
if exist "build\libs\zombiemod-1.0.1.jar" (
    echo [SUCCES] Le mod a ete compile :
    echo Fichier : build\libs\zombiemod-1.0.1.jar
    dir build\libs\zombiemod-1.0.1.jar
) else (
    echo [ECHEC] Le fichier JAR n'a pas ete cree
)
echo.
pause

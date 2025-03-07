@rem *******************************************************************************
@rem  Copyright (c) 2000, 2005 IBM Corporation and others.
@rem  All rights reserved. This program and the accompanying materials
@rem  are made available under the terms of the Eclipse Public License v1.0
@rem  which accompanies this distribution, and is available at 
@rem  http://www.eclipse.org/legal/epl-v10.html
@rem  
@rem  Contributors:
@rem      IBM Corporation - initial API and implementation
@rem      Kevin Cornell (Rational Software Corporation)
@rem ********************************************************************** 
@rem 
@rem  Usage: sh build.sh [<optional switches>] [clean]
@rem 
@rem    where the optional switches are:
@rem        -output <PROGRAM_OUTPUT>  - executable filename ("eclipse")
@rem        -library <PROGRAM_LIBRARY>- dll filename (eclipse.dll)
@rem        -os     <DEFAULT_OS>      - default Eclipse "-os" value (qnx) 
@rem        -arch   <DEFAULT_OS_ARCH> - default Eclipse "-arch" value (x86) 
@rem        -ws     <DEFAULT_WS>      - default Eclipse "-ws" value (photon)
@rem		-java   <JAVA_HOME>       - location of a Java SDK for JNI headers 
@rem 
@rem 
@rem     This script can also be invoked with the "clean" argument.
@rem
@rem NOTE: The C compiler needs to be setup. This script has been
@rem       tested against Microsoft Visual C and C++ Compiler 6.0.
@rem	
@rem Uncomment the lines below and edit MSVC_HOME to point to the
@rem correct root directory of the compiler installation, if you
@rem want this to be done by this script.
@rem 
@rem ******
@echo off

IF x.%1==x.x86_64 GOTO X86_64
IF x.%1==x.ia64 GOTO IA64

:X86
IF x.%JAVA_HOME%==x. set JAVA_HOME=C:\Dev\Java\IBM-1.5.0-20090707-SR10
set javaHome=%JAVA_HOME%
if not x.%MSVC_HOME% == x. goto MAKE
set MSVC_HOME="C:\Program Files\MS_PLAT_SDK\msvc60\VC98"
call %MSVC_HOME%\bin\vcvars32.bat
if not "%mssdk%" == "" goto MAKE
set mssdk="C:\Program Files\MS_PLAT_SDK\feb2003"
call %mssdk%\setenv.bat
IF x.%1==x.x86 shift
set defaultOSArch=x86
set makefile=make_win32.mak
GOTO MAKE

:X86_64
shift
set defaultOSArch=x86_64
IF x.%JAVA_HOME%==x. set JAVA_HOME=C:\Dev\Java\ibm-sdk-n142p-win64-x86
IF "x.%mssdk%" == "x."   set mssdk="C:\Program Files\MS_SDK_2003_R2"
set javaHome=%JAVA_HOME%
set makefile=make_win64.mak
call %mssdk%\setenv /X64 /RETAIL
GOTO MAKE

:IA64
shift
set defaultOSArch=ia64
IF x.%JAVA_HOME%==x. set JAVA_HOME=C:\Dev\Java\jdk-1_5_0_04-fcs-bin-b05-windows-ia64
IF "x.%mssdk%" == "x."   set mssdk="C:\Program Files\MS_SDK_2003_R2"
set javaHome=%JAVA_HOME%
set makefile=make_win64_ia64.mak
call %mssdk%\setenv /SRV64 /RETAIL
GOTO MAKE

:MAKE 
rem --------------------------
rem Define default values for environment variables used in the makefiles.
rem --------------------------
set programOutput=eclipse.exe
set programLibrary=eclipse.dll
set defaultOS=win32
set defaultWS=win32
set OS=Windows

rem --------------------------
rem Parse the command line arguments and override the default values.
rem --------------------------
set extraArgs=
:WHILE
if "%1" == "" goto WHILE_END
    if "%2" == ""       goto LAST_ARG

    if "%1" == "-os" (
		set defaultOS=%2
		shift
		goto NEXT )
    if "%1" == "-arch" (
		set defaultOSArch=%2
		shift
		goto NEXT )
    if "%1" == "-ws" (
		set defaultWS=%2
		shift
		goto NEXT )
    if "%1" == "-output" (
		set programOutput=%2
		shift
		goto NEXT )
	if "%1" == "-library" (
		set programLibrary=%2
		shift
		goto NEXT )
	if "%1" == "-java" (
		set javaHome=%2
		echo %javaHome%
		shift
		goto NEXT )
:LAST_ARG
        set extraArgs=%extraArgs% %1

:NEXT
    shift
    goto WHILE
:WHILE_END

rem --------------------------
rem Set up environment variables needed by the makefile.
rem --------------------------
set PROGRAM_OUTPUT=%programOutput%
set PROGRAM_LIBRARY=%programLibrary%
set DEFAULT_OS=%defaultOS%
set DEFAULT_OS_ARCH=%defaultOSArch%
set DEFAULT_WS=%defaultWS%
set OUTPUT_DIR=..\..\bin\%defaultWS%\%defaultOS%\%defaultOSArch%
set JAVA_HOME=%javaHome%

rem --------------------------
rem Run nmake to build the executable.
rem --------------------------
if "%extraArgs%" == "" goto MAKE_ALL

nmake -f %makefile% %extraArgs%
goto DONE

:MAKE_ALL
echo Building %OS% launcher. Defaults: -os %DEFAULT_OS% -arch %DEFAULT_OS_ARCH% -ws %DEFAULT_WS%
nmake -f %makefile% clean
nmake -f %makefile% %1 %2 %3 %4
goto DONE


:DONE

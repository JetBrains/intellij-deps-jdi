#!/bin/bash

find test -type f -name "*.java" -exec sed -i 's/Bootstrap\.virtualMachineManager/com.jetbrains.jdi.VirtualMachineManagerImpl.testVirtualMachineManager/g' {} \;
find test -type f -name "*.java" -exec sed -i 's/Class\.forName(\"com\.sun\.tools\.jdi\.ObjectReferenceImpl/Class\.forName(\"com\.jetbrains\.jdi\.ObjectReferenceImpl/g' {} \;
find test -type f -name "*.java" -exec sed -i 's/import com\.sun\.tools\.jdi\.ReferenceTypeImpl/import com\.jetbrains\.jdi\.ReferenceTypeImpl/g' {} \;
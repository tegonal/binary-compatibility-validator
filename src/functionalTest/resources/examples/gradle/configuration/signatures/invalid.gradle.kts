/*
 * Copyright 2016-2023 JetBrains s.r.o.
 * Use of this source code is governed by the Apache 2.0 License that can be found in the LICENSE.txt file.
 */

configure<kotlinx.validation.ApiValidationExtension> {
    klib {
        signatureVersion = 100500
    }
}

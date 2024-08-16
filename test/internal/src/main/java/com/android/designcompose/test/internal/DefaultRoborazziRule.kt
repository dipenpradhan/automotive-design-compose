/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.designcompose.test.internal

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage

const val ROBO_CAPTURE_DIR = "src/testDebug/roborazzi"

fun ComposeContentTestRule.captureRootRoboImage(screenshotName: String) {
    onRoot().captureRoboImage("$screenshotName.png")
}

fun designComposeRoborazziRule(className: String) =
    RoborazziRule(
        options =
            RoborazziRule.Options(
                outputDirectoryPath = "$ROBO_CAPTURE_DIR/$className",
                roborazziOptions =
                    RoborazziOptions(
                        compareOptions =
                            RoborazziOptions.CompareOptions(
                                imageComparator =
                                    SimpleImageComparator(maxDistance = 0.007F, hShift = 1)
                            )
                    )
            )
    )

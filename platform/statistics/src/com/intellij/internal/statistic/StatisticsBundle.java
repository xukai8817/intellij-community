/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.internal.statistic;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

/**
 * @author Denis Zhdanov
 */
public class StatisticsBundle extends DynamicBundle {

  public static String message(@NotNull @PropertyKey(resourceBundle = PATH_TO_BUNDLE) String key, @NotNull Object... params) {
    return BUNDLE.getMessage(key, params);
  }

  public static final String PATH_TO_BUNDLE = "messages.StatisticsBundle";
  private static final StatisticsBundle BUNDLE = new StatisticsBundle();

  public StatisticsBundle() {
    super(PATH_TO_BUNDLE);
  }
}

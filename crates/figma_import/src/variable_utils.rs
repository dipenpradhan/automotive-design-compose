/*
 * Copyright 2024 Google LLC
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

use crate::figma_schema;
use dc_bundle::definition::element::color_or_var::{ColorOrVar, ColorVar};
use dc_bundle::definition::element::num_or_var::{NumOrVar, NumVar};
use dc_bundle::definition::element::variable::{VariableType, VariableValueMap};
use dc_bundle::definition::element::variable_value;
use dc_bundle::definition::element::FloatColor;
use dc_bundle::definition::element::{Variable, VariableValue};
use std::collections::HashMap;

// Trait to create a XOrVar from Figma data
pub(crate) trait FromFigmaVar<VarType> {
    fn from_var(
        bound_variables: &crate::figma_schema::BoundVariables,
        var_name: &str,
        var_value: VarType,
    ) -> Self;
}
// Create a NumOrVar from Figma variable name and number value
impl FromFigmaVar<f32> for NumOrVar {
    fn from_var(
        bound_variables: &crate::figma_schema::BoundVariables,
        var_name: &str,
        var_value: f32,
    ) -> Self {
        let var = bound_variables.get_variable(var_name);
        if let Some(var) = var {
            NumOrVar::Var(NumVar { id: var, fallback: var_value })
        } else {
            NumOrVar::Num(var_value)
        }
    }
}
// Create a ColorOrVar from Figma variable name and color value
impl FromFigmaVar<&FloatColor> for ColorOrVar {
    fn from_var(
        bound_variables: &figma_schema::BoundVariables,
        var_name: &str,
        color: &FloatColor,
    ) -> Self {
        let var = bound_variables.get_variable(var_name);
        if let Some(var) = var {
            ColorOrVar::Var(ColorVar { id: var, fallback: Some(color.into()) })
        } else {
            ColorOrVar::Color(color.into())
        }
    }
}

// Create a VariableValue from figma_schema::VariableValue
fn create_variable_value(v: &figma_schema::VariableValue) -> VariableValue {
    match v {
        figma_schema::VariableValue::Boolean(b) => {
            VariableValue { value: Some(variable_value::Value::Bool(b.clone())) }
        }
        figma_schema::VariableValue::Float(f) => {
            VariableValue { value: Some(variable_value::Value::Number(f.clone())) }
        }
        figma_schema::VariableValue::String(s) => {
            VariableValue { value: Some(variable_value::Value::Text(s.clone())) }
        }
        figma_schema::VariableValue::Color(c) => {
            VariableValue { value: Some(variable_value::Value::Color(c.into())) }
        }
        figma_schema::VariableValue::Alias(a) => {
            VariableValue { value: Some(variable_value::Value::Alias(a.id.clone())) }
        }
    }
}

// Create a VariableValueMap from a hash of mode IDs to Figma VariableValues
fn create_variable_value_map(
    map: &HashMap<String, figma_schema::VariableValue>,
) -> Option<VariableValueMap> {
    let mut values_by_mode: HashMap<String, VariableValue> = HashMap::new();
    for (mode_id, value) in map.iter() {
        values_by_mode.insert(mode_id.clone(), create_variable_value(value));
    }
    Some(VariableValueMap { values_by_mode })
}

// Helper function to create a Variable
fn create_variable_helper(
    var_type: VariableType,
    common: &figma_schema::VariableCommon,
    values_by_mode: &HashMap<String, figma_schema::VariableValue>,
) -> Variable {
    Variable {
        id: common.id.clone(),
        name: common.name.clone(),
        remote: common.remote,
        key: common.key.clone(),
        variable_collection_id: common.variable_collection_id.clone(),
        var_type: var_type as i32,
        values_by_mode: create_variable_value_map(values_by_mode),
    }
}

// Create a variable from figma_schema::Variable
pub(crate) fn create_variable(v: &figma_schema::Variable) -> Variable {
    match v {
        figma_schema::Variable::Boolean { common, values_by_mode } => {
            create_variable_helper(VariableType::Bool, common, values_by_mode)
        }
        figma_schema::Variable::Float { common, values_by_mode } => {
            create_variable_helper(VariableType::Number, common, values_by_mode)
        }
        figma_schema::Variable::String { common, values_by_mode } => {
            create_variable_helper(VariableType::Text, common, values_by_mode)
        }
        figma_schema::Variable::Color { common, values_by_mode } => {
            create_variable_helper(VariableType::Color, common, values_by_mode)
        }
    }
}

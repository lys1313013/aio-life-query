-- 每条运动记录至多关联一个同域、当前用户可见的字典类型；停用类型保留历史名称。
-- 公共只读类型忽略名称覆盖；其他公共类型采用当前用户未删除的最新覆盖。
SELECT r.id AS record_id, r.user_id, d.id,
       CASE WHEN d.user_id = 0 AND COALESCE(d.is_readonly, 'N') <> 'Y'
            THEN COALESCE((SELECT o.dict_label
                           FROM user_dict_data o
                           WHERE o.user_id = r.user_id AND o.template_id = d.id
                             AND o.dict_type = d.dict_type AND o.is_deleted = 0
                           ORDER BY o.id DESC LIMIT 1), d.dict_label)
            ELSE d.dict_label END AS name
FROM exercise_record r
JOIN user_dict_data d ON d.id = r.exercise_type_id
  AND d.dict_type = 'exercise_type'
  AND (d.user_id = 0 OR d.user_id = r.user_id)
  AND d.template_id IS NULL AND d.is_deleted = 0
WHERE r.is_deleted = 0

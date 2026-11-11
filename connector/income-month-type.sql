-- 与 income-month.sql 使用相同的用户、类型和自然月粒度及复合 ID，不改变收入聚合口径。
-- 每个月收入分组至多关联一个类型；不向调用方公开用户字典表或其写入入口。
SELECT CONCAT(r.user_id, ':', CAST(r.inc_type_id AS TEXT), ':', CAST(r.month AS TEXT)) AS record_id,
       r.user_id, d.id,
       CASE WHEN d.user_id = 0 AND COALESCE(d.is_readonly, 'N') <> 'Y'
            THEN COALESCE((SELECT o.dict_label
                           FROM user_dict_data o
                           WHERE o.user_id = r.user_id AND o.template_id = d.id
                             AND o.dict_type = d.dict_type AND o.is_deleted = 0
                           ORDER BY o.id DESC LIMIT 1), d.dict_label)
            ELSE d.dict_label END AS name
FROM (
    SELECT DISTINCT user_id, inc_type_id, CAST(DATE_TRUNC('month', inc_date) AS DATE) AS month
    FROM income
    WHERE is_deleted = 0
) r
JOIN user_dict_data d ON d.id = r.inc_type_id
  AND d.dict_type = 'income_type'
  AND (d.user_id = 0 OR d.user_id = r.user_id)
  AND d.template_id IS NULL AND d.is_deleted = 0

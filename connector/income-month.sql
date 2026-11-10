-- 受控只读派生模型：按用户、收入类型、自然月汇总税前收入，保留用户维度供 Hasura 权限过滤。
-- 排除逻辑删除；不按名称推断工资类型，调用方使用实际收入类型 ID 筛选。
SELECT CONCAT(user_id, ':', COALESCE(CAST(income_type_id AS TEXT), 'null'), ':', CAST(month AS TEXT)) AS id,
       user_id, income_type_id, month, monthly_amount, 0 AS is_deleted
FROM (
    SELECT user_id, inc_type_id AS income_type_id,
           CAST(DATE_TRUNC('month', inc_date) AS DATE) AS month,
           SUM(amt) AS monthly_amount
    FROM income
    WHERE is_deleted = 0
    GROUP BY user_id, inc_type_id, CAST(DATE_TRUNC('month', inc_date) AS DATE)
) AS monthly_income

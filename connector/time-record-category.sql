-- 每条时迹至多关联一个分类，保留公共分类 ID，名称采用当前用户的有效覆盖。
-- 停用分类仍用于历史记录；删除或不属于当前用户的分类不返回。
SELECT t.id AS record_id, t.user_id, c.id,
       COALESCE((SELECT o.name
                 FROM time_tracker_category o
                 WHERE o.user_id = t.user_id AND o.template_id = c.id
                   AND o.is_deleted = 0
                 ORDER BY o.id DESC LIMIT 1), c.name) AS name
FROM time_record t
JOIN time_tracker_category c ON c.id = t.category_id
  AND (c.user_id = 0 OR c.user_id = t.user_id)
  AND c.template_id IS NULL AND c.is_deleted = 0
WHERE t.is_deleted = 0
